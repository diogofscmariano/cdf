/*!
 * Copyright 2002 - 2015 Webdetails, a Pentaho company. All rights reserved.
 *
 * This software was developed by Webdetails and is provided under the terms
 * of the Mozilla Public License, Version 2.0, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */

package org.pentaho.cdf.storage;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.mockito.Mockito.*;

import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import pt.webdetails.cpf.messaging.MockHttpServletRequest;
import pt.webdetails.cpf.messaging.MockHttpServletResponse;
import pt.webdetails.cpf.utils.CharsetHelper;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;


public class StorageApiTest {
  private static final String STORAGE_VALUE = "fake";
  private static final String USER = "fake";
  private StorageApi storageApi;
  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;

  @Before
  public void setUp() throws Exception {
    storageApi = spy( new StorageApiForTests() );
    servletRequest = new MockHttpServletRequest( "/pentaho-cdf/api/storage", (Map)new HashMap<String, String[]>() );
    servletResponse = new MockHttpServletResponse( new ObjectOutputStream( new ByteArrayOutputStream() ) );
    servletResponse.setContentType( null );
    servletResponse.setCharacterEncoding( null );
  }

  @After
  public void tearDown() {
    storageApi = null;
    servletRequest = null;
    servletResponse = null;
  }

  @Test
  public void storeTest() throws Exception {
    Assert.assertEquals( servletResponse.getContentType(), null );
    Assert.assertEquals( servletResponse.getCharacterEncoding(), null );

    storageApi.store( STORAGE_VALUE, USER, servletRequest, servletResponse );

    Assert.assertTrue( servletResponse.getContentType().equals( APPLICATION_JSON ) );
    Assert.assertTrue( servletResponse.getCharacterEncoding().equals( CharsetHelper.getEncoding() ) );
    verify( storageApi, times(1)).store( STORAGE_VALUE, USER );
  }

  @Test
  public void readTest() throws Exception {
    Assert.assertEquals( servletResponse.getContentType(), null );
    Assert.assertEquals( servletResponse.getCharacterEncoding(), null );

    storageApi.read( USER, servletRequest, servletResponse );

    Assert.assertTrue( servletResponse.getContentType().equals( APPLICATION_JSON ) );
    Assert.assertTrue( servletResponse.getCharacterEncoding().equals( CharsetHelper.getEncoding() ) );
    verify( storageApi, times(1) ).read( USER );
  }

  @Test
  public void deleteTest() throws Exception {
    Assert.assertEquals( servletResponse.getContentType(), null );
    Assert.assertEquals( servletResponse.getCharacterEncoding(), null );

    storageApi.delete( USER, servletRequest, servletResponse );

    Assert.assertTrue( servletResponse.getContentType().equals( APPLICATION_JSON ) );
    Assert.assertTrue( servletResponse.getCharacterEncoding().equals( CharsetHelper.getEncoding() ) );
    verify( storageApi, times(1) ).delete( USER );
  }
}
