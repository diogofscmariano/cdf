/*!
* Copyright 2002 - 2014 Webdetails, a Pentaho company.  All rights reserved.
*
* This software was developed by Webdetails and is provided under the terms
* of the Mozilla Public License, Version 2.0, or any later version. You may not use
* this file except in compliance with the license. If you need a copy of the license,
* please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
*
* Software distributed under the Mozilla Public License is distributed on an "AS IS"
* basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
* the license for the specific language governing your rights and limitations.
*/

package org.pentaho.cdf.context;

import java.io.OutputStream;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.StringWriter;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;
import org.json.JSONException;
import org.json.JSONObject;
import org.pentaho.cdf.CdfConstants;
import org.pentaho.cdf.context.autoinclude.AutoInclude;
import org.pentaho.cdf.environment.CdfEngine;
import org.pentaho.cdf.environment.ICdfEnvironment;
import org.pentaho.cdf.storage.StorageEngine;
import org.pentaho.cdf.util.Parameter;
import org.pentaho.cdf.views.View;
import org.pentaho.cdf.views.ViewsEngine;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.security.SecurityParameterProvider;

import pt.webdetails.cpf.InterPluginCall;
import pt.webdetails.cpf.repository.api.IContentAccessFactory;
import pt.webdetails.cpf.repository.api.IReadAccess;
import pt.webdetails.cpf.repository.util.RepositoryHelper;
import pt.webdetails.cpf.utils.PluginIOUtils;
import pt.webdetails.cpf.utils.XmlDom4JUtils;

public class ContextEngine {

  static final String SESSION_PRINCIPAL = "SECURITY_PRINCIPAL";
  private static final Log logger = LogFactory.getLog( ContextEngine.class );
  private static final String PREFIX_PARAMETER = "param";
  // embedded constants
  private static final String INITIAL_COMMENT = "/** This file is generated in cdf to allow using cdf embedded.\n" +
    "It will append to the head tag the dependencies needed, like the FULLY_QUALIFIED_URL**/\n\n";
  private static final String REQUIRE_JS_CFG_START = "var requireCfg = {waitSeconds: 30, paths: {}, shim: {}};\n\n";
  private static final String CDF_PATH = "content/pentaho-cdf/js/cdf-core-require-js-cfg.js";
  private static final String CDF_LIB_PATH = "content/pentaho-cdf/js/lib/cdf-core-lib-require-js-cfg.js";
  private static final String REQUIRE_PATH = "content/common-ui/resources/web/require.js";
  private static final String REQUIRE_START_PATH = "content/common-ui/resources/web/require-cfg.js";
  /* [settings.xml] legacy-dashboard-context: flag indicating if Dashboard.context should assume the
   * legacy structure, including deprecated attributes such as: solution, path, file, fullPath, isAdmin
   */
  private final static boolean APPLY_LEGACY_DASHBOARD_CONTEXT = Boolean.valueOf(
    StringUtils.defaultIfEmpty( CdfEngine.getEnvironment().getResourceLoader()
      .getPluginSetting( ContextEngine.class, CdfConstants.PLUGIN_SETTINGS_LEGACY_DASHBOARD_CONTEXT ), "false" ) );
  private static ContextEngine instance;
  private static String CONFIG_FILE = "dashboardContext.xml";

  private static List<AutoInclude> autoIncludes;
  private static Object autoIncludesLock = new Object();

  protected IPentahoSession userSession;

  public ContextEngine() {
  }

  public static synchronized ContextEngine getInstance() {
    if ( instance == null ) {
      instance = new ContextEngine();
    }
    return instance;
  }

  public static void clearCache() {
    // TODO figure out what to clear
    synchronized( autoIncludesLock ) {
      autoIncludes = null;
      logger.debug( "auto-includes cleared." );
    }
  }

  public static void generateContext( final OutputStream out, HashMap<String, String> paramMap, int inactiveInterval )
    throws Exception {

    String solution = StringUtils.defaultIfEmpty( paramMap.get( Parameter.SOLUTION ), StringUtils.EMPTY );
    String path = StringUtils.defaultIfEmpty( paramMap.get( Parameter.PATH ), StringUtils.EMPTY );
    String file = StringUtils.defaultIfEmpty( paramMap.get( Parameter.FILE ), StringUtils.EMPTY );
    String action = StringUtils.defaultIfEmpty( paramMap.get( Parameter.ACTION ), StringUtils.EMPTY );
    // TODO: why does view default to action?
    String viewId = StringUtils.defaultIfEmpty( paramMap.get( Parameter.VIEW ), action );
    String fullPath = RepositoryHelper.joinPaths( solution, path, file );

    // old xcdf dashboards use solution + path + action
    if ( RepositoryHelper.getExtension( action ).equals( "xcdf" ) ) {
      fullPath = RepositoryHelper.joinPaths( fullPath, action );
    }

    String dashboardContext =
      ContextEngine.getInstance().getContext( fullPath, viewId, action, paramMap, inactiveInterval );

    if ( StringUtils.isEmpty( dashboardContext ) ) {
      logger.error( "empty dashboardContext" );
    }

    PluginIOUtils.writeOut( out, dashboardContext );
  }

  protected IPentahoSession getUserSession() {
    return PentahoSessionHolder.getSession();
  }

  public String getContext( String path, String viewId, String action, Map<String, String> parameters,
                            int inactiveInterval ) {
    String username = getUserSession().getName();

    try {
      return buildContextScript( buildContext( path, username, parameters, inactiveInterval ), viewId, action,
        username );
    } catch ( JSONException e ) {
      return "";
    }
  }

  public JSONObject buildContext( String path, String username, Map<String, String> parameters, int inactiveInterval ) {
    JSONObject contextObj = new JSONObject();

    Document config = getConfigFile();

    try {
      buildContextConfig( contextObj, path, config, username );
      buildContextSessionTimeout( contextObj, inactiveInterval );
      buildContextDates( contextObj );

      contextObj.put( "user", username );
      contextObj.put( "locale", CdfEngine.getEnvironment().getLocale() );

      buildContextPaths( contextObj, path, parameters );

      SecurityParameterProvider securityParams = new SecurityParameterProvider( getUserSession() );
      contextObj.put( "roles", securityParams.getParameter( "principalRoles" ) );

      if ( APPLY_LEGACY_DASHBOARD_CONTEXT ) {
        buildLegacyStructure( contextObj, path, securityParams );
      }

      final JSONObject params = new JSONObject();
      buildContextParams( params, parameters );
      contextObj.put( "params", params );

      logger.info( "[Timing] Finished building context: "
        + ( new SimpleDateFormat( "HH:mm:ss.SSS" ) ).format( new Date() ) );

    } catch ( JSONException e ) {
      logger.error( "Error building context" );
    }

    return contextObj;
  }

  private JSONObject buildContextSessionTimeout( final JSONObject contextObj, int inactiveInterval )
    throws JSONException {
    if ( getUserSession().isAuthenticated() ) {
      contextObj.put( "sessionTimeout", inactiveInterval );
    }
    return contextObj;
  }

  private JSONObject buildContextPaths( final JSONObject contextObj, String dashboardPath,
                                        Map<String, String> parameters ) throws JSONException {
    contextObj.put( "path", dashboardPath );

    if ( parameters != null && parameters.containsKey( Parameter.SOLUTION ) ) {
      contextObj.put( Parameter.SOLUTION, parameters.get( Parameter.SOLUTION ) );
    } // TODO redo this

    return contextObj;
  }

  private JSONObject buildContextDates( final JSONObject contextObj ) throws JSONException {
    Calendar cal = Calendar.getInstance();

    long utcTime = cal.getTimeInMillis();
    contextObj.put( "serverLocalDate", utcTime + cal.getTimeZone().getOffset( utcTime ) );
    contextObj.put( "serverUTCDate", utcTime );
    return contextObj;
  }

  // Maintain backward compatibility. This is a configurable option via plugin's settings.xml
  private JSONObject buildLegacyStructure( final JSONObject contextObj, String path,
                                           SecurityParameterProvider securityParams )
    throws JSONException {

    logger.warn( "CDF: using legacy structure for Dashboard.context; " +
      "this is a deprecated structure and should not be used. This is a configurable option via plugin's settings" +
      ".xml" );

    if ( securityParams != null ) {
      contextObj.put( "isAdmin", Boolean.valueOf( (String) securityParams.getParameter( "principalAdministrator" ) ) );
    }

    if ( !StringUtils.isEmpty( path ) ) {

      if ( !contextObj.has( Parameter.FULL_PATH ) ) {
        contextObj.put( Parameter.FULL_PATH, path ); // create fullPath ctx attribute
      }

      // now parse full path into legacy structure of solution, path, file

      if ( path.startsWith( String.valueOf( RepositoryHelper.SEPARATOR ) ) ) {
        path = path.replaceFirst( String.valueOf( RepositoryHelper.SEPARATOR ), StringUtils.EMPTY );
      }

      // we must determine if this is a full path to a folder or to a file
      boolean isPathToFile = !StringUtils.isEmpty( FilenameUtils.getExtension( path ) );

      if ( isPathToFile ) {
        contextObj.put( Parameter.FILE, FilenameUtils.getName( path ) ); // create file ctx attribute
        path = path.replace( FilenameUtils.getName( path ), StringUtils.EMPTY ); // remove and continue on
      }

      path = FilenameUtils.normalizeNoEndSeparator( path );

      String[] parsedPath = path.split( String.valueOf( RepositoryHelper.SEPARATOR ) );

      if ( parsedPath != null ) {

        if ( parsedPath.length == 0 ) {

          contextObj.put( Parameter.SOLUTION, StringUtils.EMPTY ); // create solution ctx attribute
          contextObj.put( Parameter.PATH, StringUtils.EMPTY ); // create path ctx attribute

        } else if ( parsedPath.length == 1 ) {


          contextObj.put( Parameter.SOLUTION, parsedPath[ 0 ] );  // create solution ctx attribute
          contextObj.put( Parameter.PATH, StringUtils.EMPTY ); // create path ctx attribute

        } else {

          contextObj.put( Parameter.SOLUTION, parsedPath[ 0 ] );  // create solution ctx attribute
          path = path.replace( FilenameUtils.getName( parsedPath[ 0 ] ), StringUtils.EMPTY ); // remove and continue on
          path = path.replaceFirst( String.valueOf( RepositoryHelper.SEPARATOR ), StringUtils.EMPTY );
          contextObj.put( Parameter.PATH, path ); // create path ctx attribute
        }
      }
    }

    return contextObj;
  }

  private JSONObject buildContextConfig( final JSONObject contextObj, String fullPath, Document config, String user )
    throws JSONException {
    contextObj.put( "queryData", processAutoIncludes( fullPath, config ) );
    contextObj.put( "sessionAttributes", processSessionAttributes( config, user ) );

    return contextObj;
  }

  private String buildContextScript( JSONObject contextObj, String viewId, String action, String user )
    throws JSONException {
    final StringBuilder s = new StringBuilder();
    s.append( "\n<script language=\"javascript\" type=\"text/javascript\">\n" );
    s.append( "  Dashboards.context = " );
    s.append( contextObj.toString( 2 ) + "\n" );

    View view = ViewsEngine.getInstance().getView( ( viewId.isEmpty() ? action : viewId ), user );
    if ( view != null ) {
      s.append( "Dashboards.view = " );
      s.append( view.toJSON().toString( 2 ) + "\n" );
    }
    String storage = getStorage();
    if ( !"".equals( storage ) ) {
      s.append( "Dashboards.initialStorage = " );
      s.append( storage );
      s.append( "\n" );
    }
    s.append( "</script>\n" );

    return s.toString();
  }

  private JSONObject buildContextParams( final JSONObject contextObj, Map<String, String> params )
    throws JSONException {
    for ( String param : params.values() ) {
      if ( param.startsWith( PREFIX_PARAMETER ) ) {
        contextObj.put( param.substring( PREFIX_PARAMETER.length() ), params.get( param ) );
      }
    }
    return contextObj;
  }

  public JSONObject processSessionAttributes( Document config, String user ) {

    JSONObject result = new JSONObject();

    @SuppressWarnings( "unchecked" )
    List<Node> attributes = config.selectNodes( "//sessionattributes/attribute" );
    for ( Node attribute : attributes ) {

      String name = attribute.getText();
      String key = XmlDom4JUtils.getNodeText( "@name", attribute );
      if ( key == null ) {
        key = name;
      }

      try {
        result.put( key, user );
      } catch ( JSONException e ) {
        logger.error( e );
      }
    }

    return result;
  }

  private List<String> listQueries( String cda ) {
    SAXReader reader = new SAXReader();
    List<String> queryOutput = new ArrayList<String>();
    try {
      Map<String, Object> params = new HashMap<String, Object>();

      params.put( "path", cda );
      params.put( "outputType", "xml" );
      InterPluginCall ipc = new InterPluginCall( InterPluginCall.CDA, "listQueries", params );
      String reply = ipc.call();
      Document queryList = reader.read( new StringReader( reply ) );
      @SuppressWarnings( "unchecked" )
      List<Node> queries = queryList.selectNodes( "//ResultSet/Row/Col[1]" );
      for ( Node query : queries ) {
        queryOutput.add( query.getText() );
      }
    } catch ( DocumentException e ) {
      return null;
    }
    return queryOutput;
  }

  private String getStorage() {
    try {
      return StorageEngine.getInstance().read( getUserSession().getName() ).toString( 2 );
    } catch ( Exception e ) {
      logger.error( e );
      return "";
    }
  }

  /**
   * will add a json entry for each data access id in the cda queries applicable to currents dashboard.
   */
  private JSONObject processAutoIncludes( String dashboardPath, Document config ) {
    JSONObject queries = new JSONObject();
    /* Bail out immediately if CDA isn' available */
    if ( !( new InterPluginCall( InterPluginCall.CDA, "" ) ).pluginExists() ) {
      logger.warn( "Couldn't find CDA. Skipping auto-includes" );
      return queries;
    }

    /* Bail out if cdf/includes folder does not exists */
    IReadAccess autoIncludesFolder = CdfEngine.getUserContentReader( null );
    if ( !autoIncludesFolder.fileExists(
      CdfEngine.getEnvironment().getCdfPluginRepositoryDir() + CdfConstants.INCLUDES_DIR ) ) {
      return queries;
    }

    List<AutoInclude> autoIncludes = getAutoIncludes( config );
    for ( AutoInclude autoInclude : autoIncludes ) {
      if ( autoInclude.canInclude( dashboardPath ) ) {
        String cdaPath = autoInclude.getCdaPath();
        CdfEngine.getEnvironment().getCdfInterPluginBroker().addCdaQueries( queries, cdaPath );
      }
    }
    return queries;
  }

  private List<AutoInclude> getAutoIncludes( Document config ) {
    synchronized( autoIncludesLock ) {
      if ( autoIncludes == null ) {
        IReadAccess cdaRoot =
          CdfEngine.getUserContentReader( CdfEngine.getEnvironment().getCdfPluginRepositoryDir()
            + CdfConstants.INCLUDES_DIR );
        autoIncludes = AutoInclude.buildAutoIncludeList( config, cdaRoot );
      }
      return autoIncludes;
    }
  }

  private Document getConfigFile() {

    try {
      IContentAccessFactory factory = CdfEngine.getEnvironment().getContentAccessFactory();
      IReadAccess access = factory.getPluginRepositoryReader( null );

      if ( !access.fileExists( CONFIG_FILE ) ) {
        access = factory.getPluginSystemReader( null );
        if ( !access.fileExists( CONFIG_FILE ) ) {
          logger.error( CONFIG_FILE + " not found!" );
          return null;
        }
      }
      if ( logger.isDebugEnabled() ) {
        logger.debug( String.format( "Reading %s from %s", CONFIG_FILE, access ) );
      }
      return XmlDom4JUtils.getDocumentFromStream( access.getFileInputStream( CONFIG_FILE ) );

    } catch ( Exception e ) {
      logger.error( "Couldn't read context configuration file.", e );
      return null;
    }
  }

  public String generateEmbeddedContext() throws Exception {
    StringWriter output = new StringWriter();

    output.append( INITIAL_COMMENT );
    output.append( REQUIRE_JS_CFG_START );

    output.append( "// injecting document writes to append the cdf require files\n" );
    output.append( "document.write(\"<script language='javascript' type='text/javascript' src='\" + " +
      "FULLY_QUALIFIED_URL + \"" + CDF_PATH + "'></script>\");\n" );
    output.append( "document.write(\"<script language='javascript' type='text/javascript' src='\" + " +
      "FULLY_QUALIFIED_URL + \"" + CDF_LIB_PATH + "'></script>\");\n" );
    output.append( "document.write(\"<script language='javascript' type='text/javascript' src='\" + " +
      "FULLY_QUALIFIED_URL + \"" + REQUIRE_PATH + "'></script>\");\n" );
    output.append( "document.write(\"<script language='javascript' type='text/javascript' src='\" + " +
      "FULLY_QUALIFIED_URL + \"" + REQUIRE_START_PATH + "'></script>\");\n" );

    return output.toString();
  }
}
