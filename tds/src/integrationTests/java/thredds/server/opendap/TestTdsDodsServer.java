/*
 * Copyright (c) 1998-2021 University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package thredds.server.opendap;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import thredds.test.util.TestOnLocalServer;
import thredds.client.catalog.Catalog;
import thredds.client.catalog.Dataset;
import thredds.client.catalog.tools.DataFactory;
import thredds.server.catalog.TdsLocalCatalog;
import thredds.util.ContentType;
import ucar.ma2.Array;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.dataset.NetcdfDatasets;
import ucar.nc2.dt.GridCoordSystem;
import ucar.nc2.dt.grid.GeoGrid;
import ucar.nc2.util.CompareNetcdf2;
import ucar.unidata.util.StringUtil2;
import ucar.unidata.util.test.Assert2;
import ucar.unidata.util.test.TestDir;
import ucar.unidata.util.test.category.NeedsCdmUnitTest;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Formatter;
import java.util.List;

@Category(NeedsCdmUnitTest.class)
public class TestTdsDodsServer {
  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @Test
  public void checkBadRequest() {
    String endpoint = TestOnLocalServer.withHttpPath(
        "/dodsC/scanCdmUnitTests/tds/ncep/NAM_CONUS_20km_selectsurface_20100913_0000.grib2.badascii?Visibility_surface[0:1:0][0:1:0][0:1:0]");
    TestOnLocalServer.getContent(endpoint, 400, (ContentType) null);
  }

  @Test
  public void testGridArrayAscii() {
    String endpoint = TestOnLocalServer.withHttpPath(
        "/dodsC/scanCdmUnitTests/tds/ncep/NAM_CONUS_20km_selectsurface_20100913_0000.grib2.ascii?Visibility_surface[0:1:0][0:1:0][0:1:0]");
    byte[] result = TestOnLocalServer.getContent(endpoint, 200, (ContentType) null);
    Assert.assertNotNull(result);
    String results = new String(result, StandardCharsets.UTF_8);
    assert results.contains("scanCdmUnitTests/tds/ncep/NAM_CONUS_20km_selectsurface_20100913_0000.grib2");
    assert results.contains("15636.879");
  }

  @Test
  public void testTooBigDods() throws IOException {
    String endpoint = TestOnLocalServer.withHttpPath(
        "dodsC/HRRR/analysis/TP.dods?u-component_of_wind_isobaric&Temperature_isobaric&v-component_of_wind_isobaric");
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpGet httpGet = new HttpGet(endpoint);
      HttpResponse response = httpClient.execute(httpGet);
      StatusLine status = response.getStatusLine();
      Assert.assertEquals(status.getStatusCode(), 403);
      HttpEntity entity = response.getEntity();
      Assert.assertEquals(entity.getContentType().getValue(), MediaType.TEXT_PLAIN.toString());
      String responseString = EntityUtils.toString(entity, "UTF-8");
      Assert.assertTrue(responseString.toLowerCase().contains("request too large"));
    }
  }

  @Test
  public void testTooBigAscii() throws IOException {
    String endpoint = TestOnLocalServer.withHttpPath(
        "dodsC/HRRR/analysis/TP.ascii?u-component_of_wind_isobaric&Temperature_isobaric&v-component_of_wind_isobaric");
    try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
      HttpGet httpGet = new HttpGet(endpoint);
      HttpResponse response = httpClient.execute(httpGet);
      StatusLine status = response.getStatusLine();
      Assert.assertEquals(status.getStatusCode(), 403);
      HttpEntity entity = response.getEntity();
      Assert.assertEquals(entity.getContentType().getValue(), MediaType.TEXT_PLAIN.toString());
      String responseString = EntityUtils.toString(entity, "UTF-8");
      Assert.assertTrue(responseString.toLowerCase().contains("request too large"));
    }
  }

  @Test
  public void testUrlReading() throws IOException {
    doOne(TestOnLocalServer.withHttpPath("dodsC/scanCdmUnitTests/tds/ncep/NAM_Alaska_22km_20100504_0000.grib1"));
    doOne(
        TestOnLocalServer.withHttpPath("dodsC/scanCdmUnitTests/tds/ncep/NAM_Alaska_45km_conduit_20100913_0000.grib2"));
  }

  /*
   * Dataset {
   * Grid {
   * ARRAY:
   * Float32 Visibility_surface[time = 1][y = 1][x = 1];
   * MAPS:
   * Int32 time[time = 1];
   * Float32 y[y = 1];
   * Float32 x[x = 1];
   * } Visibility_surface;
   * } testTdsScan/ncep/NAM_CONUS_20km_selectsurface_20100913_0000.grib2;
   * ---------------------------------------------
   * Visibility_surface.Visibility_surface[1][1][1]
   * [0][0], 15636.879
   * 
   * Visibility_surface.time[1]
   * 0
   * 
   * Visibility_surface.y[1]
   * -832.6978
   * 
   * Visibility_surface.x[1]
   * -4226.1084
   */

  @Test
  public void testSingleDataset() throws IOException {
    Catalog cat = TdsLocalCatalog.open(null);

    Dataset ds = cat.findDatasetByID("testDataset2");
    assert (ds != null) : "cant find dataset 'testDataset'";
    assert ds.getFeatureType() == FeatureType.GRID;

    DataFactory fac = new DataFactory();
    try (DataFactory.Result dataResult = fac.openFeatureDataset(ds, null)) {
      Assert.assertTrue(dataResult.errLog.toString(), !dataResult.fatalError);
      Assert.assertNotNull(dataResult.featureDataset);
      Assert.assertEquals(ucar.nc2.dt.grid.GridDataset.class, dataResult.featureDataset.getClass());

      ucar.nc2.dt.grid.GridDataset gds = (ucar.nc2.dt.grid.GridDataset) dataResult.featureDataset;
      String gridName = "Z_sfc";
      VariableSimpleIF vs = gds.getDataVariable(gridName);
      Assert.assertNotNull(gridName, vs);

      GeoGrid grid = gds.findGridByShortName(gridName);
      Assert.assertNotNull(gridName, grid);

      GridCoordSystem gcs = grid.getCoordinateSystem();
      Assert.assertNotNull(gcs);
      assert null == gcs.getVerticalAxis();

      CoordinateAxis time = gcs.getTimeAxis();
      Assert.assertNotNull("time axis", time);
      Assert.assertEquals(1, time.getSize());
      Array data = time.read();
      Assert2.assertNearlyEquals(102840.0, data.getFloat(0));
    }
  }

  private void doOne(String urlString) throws IOException {
    logger.debug("Open and read {}", urlString);

    NetcdfFile ncd = NetcdfDatasets.openFile(urlString, null);
    assert ncd != null;

    // pick a random variable to read
    List vlist = ncd.getVariables();
    int n = vlist.size();
    assert n > 0;
    Variable v = (Variable) vlist.get(n / 2);
    logger.debug("Read all data from {}", v.getFullName());
    Array data = v.read();
    assert data.getSize() == v.getSize();

    ncd.close();
  }

  @Test
  public void testCompareWithFile() throws IOException {
    final String urlPrefix = TestOnLocalServer.withHttpPath("/dodsC/scanCdmUnitTests/tds/opendap/");
    final String dirName = TestDir.cdmUnitTestDir + "tds/opendap/"; // read all files from this dir

    TestDir.actOnAll(dirName, TestDir.FileFilterSkipSuffix(".gbx8 .gbx9 .ncx .ncx2 .ncx3"), new TestDir.Act() {
      public int doAct(String filename) throws IOException {
        filename = StringUtil2.replace(filename, '\\', "/");
        filename = StringUtil2.remove(filename, dirName);
        String dodsUrl = urlPrefix + filename;
        String localPath = dirName + filename;
        logger.debug("--Compare {} to {}", localPath, dodsUrl);

        NetcdfDataset org_ncfile = NetcdfDatasets.openDataset(localPath);
        NetcdfDataset dods_file = NetcdfDatasets.openDataset(dodsUrl);

        Formatter f = new Formatter();
        CompareNetcdf2 mind = new CompareNetcdf2(f, false, false, false);
        boolean ok = mind.compare(org_ncfile, dods_file, new TestDODScompareWithFiles.DodsObjFilter());
        if (!ok) {
          logger.debug("--Compare {}", filename);
          logger.debug("  {}", f);
        }
        Assert.assertTrue(localPath + " != " + dodsUrl, ok);

        return 1;
      }
    }, false);
  }
}
