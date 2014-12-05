/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fluo.api.config;

import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.api.data.Span;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit test for ScannerConfiguration class
 */
public class ScannerConfigurationTest {
  
  @Test
  public void testSetGet() {
    
    ScannerConfiguration config = new ScannerConfiguration();
    Assert.assertEquals(new Span(), config.getSpan());
    Assert.assertEquals(0, config.getColumns().size());
    
    config = new ScannerConfiguration();
    config.setSpan(Span.exact("row1"));
    Assert.assertEquals(Span.exact("row1"), config.getSpan());
    Assert.assertEquals(0, config.getColumns().size());
    
    config = new ScannerConfiguration();
    config.fetchColumnFamily(Bytes.wrap("cf1"));
    Assert.assertEquals(1, config.getColumns().size());
    Assert.assertEquals(new Column("cf1"), config.getColumns().iterator().next());
    
    config = new ScannerConfiguration();
    config.fetchColumn(Bytes.wrap("cf2"), Bytes.wrap("cq2"));
    Assert.assertEquals(1, config.getColumns().size());
    Assert.assertEquals(new Column("cf2", "cq2"), config.getColumns().iterator().next());
    
    config = new ScannerConfiguration();
    config.fetchColumnFamily(Bytes.wrap("a"));
    config.fetchColumnFamily(Bytes.wrap("b"));
    config.fetchColumnFamily(Bytes.wrap("a"));
    Assert.assertEquals(2, config.getColumns().size());
    
    config.clearColumns();
    Assert.assertEquals(0, config.getColumns().size());
  }
  
  @Test
  public void testNullSet() {
    
    ScannerConfiguration config = new ScannerConfiguration();
    
    try {
      config.setSpan(null);
      Assert.fail();
    } catch (NullPointerException e) { }
    
    try {
      config.fetchColumnFamily(null);
      Assert.fail();
    } catch (NullPointerException e) { }
    
    try {
      config.fetchColumn(null, Bytes.wrap("qual"));
      Assert.fail();
    } catch (NullPointerException e) { }
    
    try {
      config.fetchColumn(Bytes.wrap("fam"), null);
      Assert.fail();
    } catch (NullPointerException e) { }
    
  }
}