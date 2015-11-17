package org.pentaho.cdf.environment.paths;

import pt.webdetails.cpf.Util;
import pt.webdetails.cpf.context.api.IUrlProvider;

public class CdfApiPathProvider implements ICdfApiPathProvider {

  private IUrlProvider urlProvider;

  public CdfApiPathProvider( IUrlProvider urlProvider ) {
    this.urlProvider = urlProvider;
  }

  @Override
  public String getRendererBasePath() {
    return Util.joinPath( urlProvider.getPluginBaseUrl(), "renderer" );
  }

  @Override
  public String getPluginStaticBaseUrl() {
    return urlProvider.getPluginStaticBaseUrl();
  }
  
  @Override
  public String getViewActionUrl() {
    return "ViewAction?path={path}/{name}";
  }

  @Override
  public String getWebappContextRoot() { return urlProvider.getWebappContextRoot(); }

  @Override
  public String getResourcesBasePath() {
    return Util.joinPath( urlProvider.getPluginBaseUrl(), "resources" );
  }
}
