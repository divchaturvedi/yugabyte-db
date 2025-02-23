// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.commissioner.tasks;

import static com.yugabyte.yw.common.AssertHelper.assertAuditEntry;
import static com.yugabyte.yw.common.AssertHelper.assertBadRequest;
import static com.yugabyte.yw.common.AssertHelper.assertOk;
import static com.yugabyte.yw.common.AssertHelper.assertPlatformException;
import static com.yugabyte.yw.common.TestHelper.createTempFile;
import static com.yugabyte.yw.models.TaskInfo.State.Success;
import static junit.framework.TestCase.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static play.mvc.Http.Status.BAD_REQUEST;
import static play.test.Helpers.contentAsString;

import com.amazonaws.services.ec2.model.Image;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.yugabyte.yw.cloud.CloudAPI;
import com.yugabyte.yw.commissioner.Common;
import com.yugabyte.yw.common.ConfigHelper;
import com.yugabyte.yw.common.FakeApiHelper;
import com.yugabyte.yw.common.ModelFactory;
import com.yugabyte.yw.common.PlatformServiceException;
import com.yugabyte.yw.common.ShellResponse;
import com.yugabyte.yw.models.AccessKey;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.AvailabilityZoneDetails;
import com.yugabyte.yw.models.ImageBundle;
import com.yugabyte.yw.models.ImageBundleDetails;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.ProviderDetails;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.RegionDetails;
import com.yugabyte.yw.models.TaskInfo;
import com.yugabyte.yw.models.Users;
import com.yugabyte.yw.models.helpers.CloudInfoInterface;
import com.yugabyte.yw.models.helpers.TaskType;
import com.yugabyte.yw.models.helpers.provider.AWSCloudInfo;
import com.yugabyte.yw.models.helpers.provider.GCPCloudInfo;
import com.yugabyte.yw.models.helpers.provider.region.KubernetesRegionInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.mockito.Mockito;
import play.libs.Json;
import play.mvc.Result;

@Slf4j
public class CloudProviderEditTest extends CommissionerBaseTest {

  private Provider provider;
  private Users user;

  private Result editProvider(Provider provider, boolean validate) {
    return editProvider(provider, validate, false);
  }

  private Result editProvider(Provider provider, boolean validate, boolean ignoreValidationErrors) {
    return editProvider(Json.toJson(provider), validate, ignoreValidationErrors);
  }

  private Result editProvider(JsonNode providerJson, boolean validate) {
    return editProvider(providerJson, validate, false);
  }

  private Result editProvider(
      JsonNode providerJson, boolean validate, boolean ignoreValidationErrors) {
    String uuidStr = providerJson.get("uuid").asText();
    return FakeApiHelper.doRequestWithAuthTokenAndBody(
        app,
        "PUT",
        "/api/customers/"
            + defaultCustomer.getUuid()
            + "/providers/"
            + uuidStr
            + String.format(
                "/edit?validate=%s&ignoreValidationErrors=%s", validate, ignoreValidationErrors),
        user.createAuthToken(),
        providerJson);
  }

  private Result getProvider(UUID providerUUID) {
    return FakeApiHelper.doRequestWithAuthToken(
        app,
        "GET",
        "/api/customers/" + defaultCustomer.getUuid() + "/providers/" + providerUUID,
        user.createAuthToken());
  }

  @Override
  public void setUp() {
    super.setUp();
    user = ModelFactory.testUser(defaultCustomer);
    provider =
        Provider.create(
            defaultCustomer.getUuid(), Common.CloudType.aws, "test", new ProviderDetails());
    when(mockAWSCloudImpl.getPrivateKeyAlgoOrBadRequest(any())).thenReturn("AHAHA");
    AccessKey.create(
        provider.getUuid(), AccessKey.getDefaultKeyCode(provider), new AccessKey.KeyInfo());
    Region region = Region.create(provider, "us-west-1", "us-west-1", "yb-image1");
    AvailabilityZone.createOrThrow(region, "r1z1", "zone 1 reg 1", "subnet 1");
    provider = Provider.getOrBadRequest(provider.getUuid());
    provider.setUsabilityState(Provider.UsabilityState.READY);
    provider.setLastValidationErrors(null);
    provider.save();
    Map<String, Object> regionMetadata =
        ImmutableMap.<String, Object>builder()
            .put("name", "Mock Region")
            .put("latitude", 36.778261)
            .put("longitude", -119.417932)
            .build();

    when(mockConfigHelper.getRegionMetadata(Common.CloudType.aws))
        .thenReturn(
            ImmutableMap.of(
                // AWS regions to use.
                "us-west-1", regionMetadata,
                "us-west-2", regionMetadata,
                "us-east-1", regionMetadata));

    when(mockConfigHelper.getConfig(ConfigHelper.ConfigType.GKEKubernetesRegionMetadata))
        .thenReturn(
            ImmutableMap.of(
                // GCP regions to use.
                "us-west1", regionMetadata,
                "us-west2", regionMetadata,
                "us-east1", regionMetadata));
    String kubeFile = createTempFile("test2.conf", "test5678");
    when(mockAccessManager.createKubernetesConfig(anyString(), anyMap(), anyBoolean()))
        .thenReturn(kubeFile);
  }

  private void setUpCredsValidation(boolean valid) {
    CloudAPI mockCloudAPI = mock(CloudAPI.class);
    Mockito.doNothing().when(mockCloudAPI).validateInstanceTemplate(any(), any());
    when(mockCloudAPI.isValidCreds(any(), any())).thenReturn(valid);
    when(mockCloudAPIFactory.get(any())).thenReturn(mockCloudAPI);
  }

  @Test
  public void testAddRegion() throws InterruptedException {
    setUpCredsValidation(true);
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    Region region = new Region();
    region.setProviderCode(provider.getCloudCode().name());
    region.setCode("us-west-2");
    region.setName("us-west-2");
    region.setVnetName("vnet");
    region.setSecurityGroupId("sg-1");
    region.setYbImage("yb-image2");
    AvailabilityZone zone = new AvailabilityZone();
    zone.setCode("z1 r2");
    zone.setName("zone 2");
    zone.setSubnet("subnet 2");
    region.setZones(Collections.singletonList(zone));
    editProviderReq.getRegions().add(region);

    UUID taskUUID = doEditProvider(editProviderReq, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    Provider resultProvider = Provider.getOrBadRequest(provider.getUuid());
    assertEquals(
        new HashSet<>(Arrays.asList("us-west-1", "us-west-2")),
        resultProvider.getRegions().stream().map(r -> r.getCode()).collect(Collectors.toSet()));
    for (Region reg : resultProvider.getRegions()) {
      assertEquals(1, reg.getZones().size());
    }
    List<TaskInfo> subTasks = taskInfo.getSubTasks();
    Map<Integer, List<TaskInfo>> subTasksByPosition =
        subTasks.stream().collect(Collectors.groupingBy(TaskInfo::getPosition));
    int position = 0;
    assertTaskType(subTasksByPosition.get(position++), TaskType.CloudRegionSetup);
    assertTaskType(subTasksByPosition.get(position++), TaskType.CloudAccessKeySetup);
    assertTaskType(subTasksByPosition.get(position++), TaskType.CloudInitializer);
  }

  @Test
  public void testAddRegionNoZonesFail() throws InterruptedException {
    setUpCredsValidation(true);
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    Region region = new Region();
    region.setProviderCode(provider.getCloudCode().name());
    region.setCode("us-west-2");
    region.setName("us-west-2");
    region.setVnetName("vnet");
    region.setSecurityGroupId("sg-1");
    region.setYbImage("yb-image2");
    editProviderReq.getRegions().add(region);
    verifyEditError(
        editProviderReq, false, "Zone info needs to be specified for region: us-west-2");
  }

  @Test
  public void testAddAz() throws InterruptedException {
    setUpCredsValidation(true);
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    Region region = editProviderReq.getRegions().get(0);
    AvailabilityZone zone = new AvailabilityZone();
    zone.setCode("z2r1");
    zone.setName("zone 2");
    zone.setSubnet("subnet 2");
    region.getZones().add(zone);
    UUID taskUUID = doEditProvider(editProviderReq, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    Provider resultProvider = Provider.getOrBadRequest(provider.getUuid());
    assertEquals(2, resultProvider.getRegions().get(0).getZones().size());
  }

  @Test
  public void testVersionFail() {
    setUpCredsValidation(true);
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    editProviderReq.setVersion(editProviderReq.getVersion() - 1);
    Region region = editProviderReq.getRegions().get(0);
    AvailabilityZone zone = new AvailabilityZone();
    zone.setCode("z2 r1");
    zone.setName("zone 2");
    zone.setSubnet("subnet 2");
    region.getZones().add(zone);
    verifyEditError(editProviderReq, false, "Provider has changed, please refresh and try again");
  }

  @Test
  public void testEditProviderDeleteRegion() throws InterruptedException {
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    editProviderReq.getRegions().get(0).setActive(false);
    UUID taskUUID = doEditProvider(editProviderReq, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    Provider resultProvider = Provider.getOrBadRequest(provider.getUuid());
    assertTrue("Region is deleted", resultProvider.getRegions().isEmpty());
  }

  @Test
  public void testEditProviderModifyAZs() throws InterruptedException {
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    editProviderReq.getRegions().get(0).getZones().get(0).setSubnet("subnet-changed");
    UUID taskUUID = doEditProvider(editProviderReq, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    Provider resultProvider = Provider.getOrBadRequest(provider.getUuid());
    assertEquals(
        "subnet-changed", resultProvider.getRegions().get(0).getZones().get(0).getSubnet());
  }

  @Test
  public void testAwsProviderDetailsEdit() throws InterruptedException {
    ProviderDetails details = provider.getDetails();
    details.sshUser = "test-user";
    details.setCloudInfo(new ProviderDetails.CloudInfo());
    details.getCloudInfo().aws = new AWSCloudInfo();
    details.getCloudInfo().aws.awsAccessKeyID = "Test AWS Access Key ID";
    details.getCloudInfo().aws.awsAccessKeySecret = "Test AWS Access Key Secret";
    provider.save();
    Result providerRes = getProvider(this.provider.getUuid());
    JsonNode providerJson = Json.parse(contentAsString(providerRes));
    ObjectNode detailsJson = (ObjectNode) providerJson.get("details");
    detailsJson.put("sshUser", "modified-ssh-user");
    ((ObjectNode) providerJson).set("details", detailsJson);
    Result result = editProvider(providerJson, false);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(this.provider.getUuid(), UUID.fromString(json.get("resourceUUID").asText()));
    waitForTask(UUID.fromString(json.get("taskUUID").asText()));
    this.provider = Provider.getOrBadRequest(this.provider.getUuid());
    assertEquals("modified-ssh-user", this.provider.getDetails().sshUser);
    assertEquals(
        "Test AWS Access Key ID", this.provider.getDetails().getCloudInfo().aws.awsAccessKeyID);
    assertAuditEntry(1, defaultCustomer.getUuid());
  }

  @Test
  public void testEditProviderKubernetesConfigEdit() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();
    k8sProvider.getDetails().getCloudInfo().kubernetes.setKubeConfigName("test2.conf");
    k8sProvider.getDetails().getCloudInfo().kubernetes.setKubeConfigContent("test5678");
    Result result = editProvider(k8sProvider, false);

    assertOk(result);
    assertAuditEntry(2, defaultCustomer.getUuid());
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(k8sProvider.getUuid(), UUID.fromString(json.get("resourceUUID").asText()));
    waitForTask(UUID.fromString(json.get("taskUUID").asText()));
    k8sProvider = Provider.getOrBadRequest(k8sProvider.getUuid());
    Map<String, String> config = CloudInfoInterface.fetchEnvVars(k8sProvider);
    Path path = Paths.get(config.get("KUBECONFIG"));
    try {
      List<String> contents = Files.readAllLines(path);
      assertEquals(contents.get(0), "test5678");
    } catch (IOException e) {
      // Do nothing
    }
  }

  @Test
  public void testEditProviderWithAWSProviderType() throws InterruptedException {
    Result providerRes = getProvider(provider.getUuid());
    JsonNode bodyJson = Json.parse(contentAsString(providerRes));
    Provider provider = Json.fromJson(bodyJson, Provider.class);
    provider.getDetails().getCloudInfo().aws.awsHostedZoneId = "1234";
    mockDnsManagerListSuccess("test");
    Result result = editProvider(Json.toJson(provider), false);
    verify(mockDnsManager, times(1)).listDnsRecord(any(), any());
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(provider.getUuid(), UUID.fromString(json.get("resourceUUID").asText()));
    waitForTask(UUID.fromString(json.get("taskUUID").asText()));
    provider = Provider.getOrBadRequest(provider.getUuid());
    assertEquals("1234", provider.getDetails().getCloudInfo().aws.getAwsHostedZoneId());
    assertAuditEntry(1, defaultCustomer.getUuid());
  }

  @Test
  public void testEditProviderKubernetes() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();
    k8sProvider.getDetails().getCloudInfo().kubernetes.setKubernetesStorageClass("slow");

    Result result = editProvider(k8sProvider, false);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    assertEquals(k8sProvider.getUuid(), UUID.fromString(json.get("resourceUUID").asText()));
    waitForTask(UUID.fromString(json.get("taskUUID").asText()));
    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    Map<String, String> config = CloudInfoInterface.fetchEnvVars(p);
    assertEquals("slow", config.get("STORAGE_CLASS"));
    assertAuditEntry(2, defaultCustomer.getUuid());
  }

  @Test
  public void testModifyGCPProviderCredentials() throws InterruptedException {
    Provider gcpProvider =
        Provider.create(
            defaultCustomer.getUuid(), Common.CloudType.gcp, "test", new ProviderDetails());

    gcpProvider.getDetails().setCloudInfo(new ProviderDetails.CloudInfo());
    GCPCloudInfo gcp = new GCPCloudInfo();
    gcpProvider.getDetails().getCloudInfo().setGcp(gcp);
    gcp.setGceProject("gce_proj");
    gcp.setGceApplicationCredentials(Json.newObject().put("GCE_EMAIL", "test@yugabyte.com"));
    gcpProvider.save();
    ((ObjectNode) gcpProvider.getDetails().getCloudInfo().getGcp().getGceApplicationCredentials())
        .put("client_id", "Client ID");
    UUID taskUUID = doEditProvider(gcpProvider, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    gcpProvider = Provider.getOrBadRequest(gcpProvider.getUuid());
    assertEquals(
        "Client ID",
        ((ObjectNode)
                gcpProvider.getDetails().getCloudInfo().getGcp().getGceApplicationCredentials())
            .get("client_id")
            .textValue());
  }

  @Test
  public void testEditProviderValidationFail() throws InterruptedException {
    setUpCredsValidation(false);
    verifyEditError(provider, true, "Invalid AWS Credentials");
  }

  @Test
  public void testEditProviderValidationOk() throws InterruptedException {
    provider.setLastValidationErrors(Json.newObject().put("error", "something wrong"));
    provider.setUsabilityState(Provider.UsabilityState.ERROR);
    provider.save();
    provider.setName("new name");
    when(mockAWSCloudImpl.getPrivateKeyAlgoOrBadRequest(any())).thenReturn("RSA");
    Image image = new Image();
    image.setArchitecture("x86_64");
    image.setRootDeviceType("ebs");
    image.setPlatformDetails("linux/UNIX");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), anyString())).thenReturn(image);
    UUID taskUUID = doEditProvider(provider, true);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(Success, taskInfo.getTaskState());
    Provider newProvider = Provider.getOrBadRequest(provider.getUuid());
    assertEquals("new name", newProvider.getName());
    assertEquals(Provider.UsabilityState.READY, newProvider.getUsabilityState());
    assertNull(newProvider.getLastValidationErrors());
  }

  @Test
  public void testValidationOKToError() throws InterruptedException {
    assertEquals(Provider.UsabilityState.READY, provider.getUsabilityState());
    assertNull(provider.getLastValidationErrors());
    provider.setName("new name");
    when(mockAWSCloudImpl.describeImageOrBadRequest(any(), any(), anyString()))
        .thenThrow(
            new PlatformServiceException(BAD_REQUEST, "AMI details extraction failed: Not found"));
    UUID taskUUID = doEditProvider(provider, true, true);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(Success, taskInfo.getTaskState());
    provider = Provider.getOrBadRequest(provider.getUuid());
    assertNotNull(provider.getLastValidationErrors());
    assertEquals(
        Json.parse("[\"AMI details extraction failed: Not found\"]"),
        provider.getLastValidationErrors().get("error").get("data.REGION.us-west-1.IMAGE"));
    assertEquals(Provider.UsabilityState.READY, provider.getUsabilityState());
    assertEquals("new name", provider.getName());
  }

  @Test
  public void testAwsProviderDetailsEditMaskedKeys() throws InterruptedException {
    ProviderDetails details = provider.getDetails();
    details.sshUser = "test-user";
    details.setCloudInfo(new ProviderDetails.CloudInfo());
    details.getCloudInfo().aws = new AWSCloudInfo();
    details.getCloudInfo().aws.awsAccessKeyID = "Test AWS Access Key ID";
    details.getCloudInfo().aws.awsAccessKeySecret = "Test AWS Access Key Secret";
    provider.save();
    Provider editProviderReq = Provider.getOrBadRequest(provider.getUuid());
    JsonNode providerJson = Json.toJson(editProviderReq);
    ObjectNode detailsJson = (ObjectNode) providerJson.get("details");
    ObjectNode cloudInfo = (ObjectNode) detailsJson.get("cloudInfo");
    ObjectNode aws = (ObjectNode) cloudInfo.get("aws");
    cloudInfo.set("aws", aws);
    detailsJson.set("cloudInfo", cloudInfo);
    aws.put("awsAccessKeyID", "Updated AWS Access Key ID");
    ((ObjectNode) providerJson).set("details", detailsJson);
    Result result = editProvider(providerJson, false);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    UUID taskUUID = UUID.fromString(json.get("taskUUID").asText());
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    provider = Provider.getOrBadRequest(provider.getUuid());
    assertEquals(
        "Updated AWS Access Key ID", provider.getDetails().getCloudInfo().aws.awsAccessKeyID);
    assertAuditEntry(1, defaultCustomer.getUuid());
  }

  @Test
  public void testK8sProviderEditDetails() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    p.getDetails().getCloudInfo().getKubernetes().setKubernetesProvider("GKE2");

    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());
    assertEquals(p.getDetails().getCloudInfo().getKubernetes().getKubernetesProvider(), "GKE2");
  }

  @Test
  public void testK8sProviderEditAddZone() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    ObjectNode providerJson = (ObjectNode) Json.toJson(p);
    JsonNode region = providerJson.get("regions").get(0);
    ArrayNode zones = (ArrayNode) region.get("zones");

    ObjectNode zone = Json.newObject();
    zone.put("name", "Zone 2");
    zone.put("code", "zone-2");
    zone.put("subnet", "subnet-2");
    zones.add(zone);
    ((ObjectNode) region).set("zones", zones);
    ArrayNode regionsNode = Json.newArray();
    regionsNode.add(region);
    providerJson.set("regions", regionsNode);
    p = Provider.getOrBadRequest(k8sProvider.getUuid());
    assertEquals(1, p.getRegions().get(0).getZones().size());

    Result result = editProvider(providerJson, false);
    JsonNode json = Json.parse(contentAsString(result));
    UUID taskUUID = UUID.fromString(json.get("taskUUID").asText());
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());

    assertEquals(2, p.getRegions().get(0).getZones().size());
    assertEquals("zone-2", p.getRegions().get(0).getZones().get(1).getCode());
  }

  @Test
  public void testK8sProviderEditModifyZone() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    assertEquals(1, p.getRegions().get(0).getZones().size());
    AvailabilityZone az = p.getRegions().get(0).getZones().get(0);
    AvailabilityZoneDetails details = az.getDetails();
    details.setCloudInfo(new AvailabilityZoneDetails.AZCloudInfo());
    details.getCloudInfo().setKubernetes(new KubernetesRegionInfo());
    details.getCloudInfo().getKubernetes().setKubernetesStorageClass("Storage class");

    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());

    assertEquals(1, p.getRegions().get(0).getZones().size());
    assertEquals(
        "Storage class",
        p.getRegions()
            .get(0)
            .getZones()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubernetesStorageClass());
  }

  @Test
  public void testK8sProviderDeleteZone() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    assertEquals(1, p.getRegions().get(0).getZones().size());
    AvailabilityZone az = p.getRegions().get(0).getZones().get(0);
    az.setActive(false);

    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());

    List<AvailabilityZone> azs = AvailabilityZone.getAZsForRegion(p.getRegions().get(0).getUuid());

    assertEquals(0, azs.size());
  }

  @Test
  public void testK8sProviderAddRegion() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    ObjectNode providerJson = (ObjectNode) Json.toJson(p);
    ArrayNode regions = (ArrayNode) providerJson.get("regions");

    ObjectNode region = Json.newObject();
    region.put("name", "Region 2");
    region.put("code", "us-west2");

    ArrayNode zones = Json.newArray();
    ObjectNode zone = Json.newObject();
    zone.put("name", "Zone 2");
    zone.put("code", "zone-2");
    zone.put("subnet", "subnet-2");
    zones.add(zone);
    region.set("zones", zones);

    regions.add(region);
    providerJson.set("regions", regions);

    Result result = editProvider(providerJson, false);
    JsonNode json = Json.parse(contentAsString(result));
    UUID taskUUID = UUID.fromString(json.get("taskUUID").asText());
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());

    assertEquals(2, p.getRegions().size());
    assertEquals("us-west2", p.getRegions().get(1).getCode());
    assertEquals("zone-2", p.getRegions().get(1).getZones().get(0).getCode());
  }

  @Test
  public void testK8sProviderEditModifyRegion() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();
    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    Region region = p.getRegions().get(0);
    region.setDetails(new RegionDetails());
    region.getDetails().setCloudInfo(new RegionDetails.RegionCloudInfo());
    region.getDetails().getCloudInfo().setKubernetes(new KubernetesRegionInfo());
    region
        .getDetails()
        .getCloudInfo()
        .getKubernetes()
        .setKubernetesStorageClass("Updating storage class");

    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());

    p = Provider.getOrBadRequest(p.getUuid());
    assertEquals(1, p.getRegions().size());
    assertEquals(
        "Updating storage class",
        p.getRegions()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubernetesStorageClass());
  }

  @Test
  public void testK8sProviderConfigAtMultipleLevels() {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    p.getDetails().getCloudInfo().getKubernetes().setKubeConfigName("Test-1");
    AvailabilityZoneDetails details = p.getRegions().get(0).getZones().get(0).getDetails();

    details.setCloudInfo(new AvailabilityZoneDetails.AZCloudInfo());
    details.getCloudInfo().setKubernetes(new KubernetesRegionInfo());
    details.getCloudInfo().getKubernetes().setKubeConfigName("Test-2");

    verifyEditError(p, false, "Kubeconfig can't be at two levels");
  }

  @Test
  public void testK8sProviderConfigEditAtZoneLevel() throws InterruptedException {
    Provider k8sProvider = createK8sProvider(false);
    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    p.getRegions()
        .get(0)
        .getZones()
        .get(0)
        .getDetails()
        .getCloudInfo()
        .getKubernetes()
        .setKubeConfigName("Test-2");

    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());

    assertNull(
        p.getRegions()
            .get(0)
            .getZones()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubeConfigName());

    assertNotNull(
        p.getRegions()
            .get(0)
            .getZones()
            .get(0)
            .getDetails()
            .getCloudInfo()
            .getKubernetes()
            .getKubeConfig());
  }

  @Test
  public void testK8sProviderConfigEditAtProviderLevel() throws InterruptedException {
    Provider k8sProvider = createK8sProvider();

    Provider p = Provider.getOrBadRequest(k8sProvider.getUuid());
    p.getDetails().getCloudInfo().getKubernetes().setKubernetesStorageClass("Test-2");

    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());
    p = Provider.getOrBadRequest(p.getUuid());
    assertEquals(
        "Test-2", p.getDetails().getCloudInfo().getKubernetes().getKubernetesStorageClass());
  }

  @Test
  public void testProviderNameChangeWithExistingName() {
    Provider p = ModelFactory.newProvider(defaultCustomer, Common.CloudType.aws);
    Provider p2 = ModelFactory.newProvider(defaultCustomer, Common.CloudType.aws, "aws-2");
    Result providerRes = getProvider(p.getUuid());
    ObjectNode bodyJson = (ObjectNode) Json.parse(contentAsString(providerRes));
    bodyJson.put("name", "aws-2");
    Result result = assertPlatformException(() -> editProvider(bodyJson, false));
    assertBadRequest(result, "Provider with name aws-2 already exists.");
  }

  @Test
  public void testImageBundleEditProvider() throws InterruptedException {
    Provider p = ModelFactory.newProvider(defaultCustomer, Common.CloudType.gcp);
    Region.create(p, "us-west-1", "us-west-1", "yb-image1");
    ImageBundleDetails details = new ImageBundleDetails();
    Map<String, ImageBundleDetails.BundleInfo> regionImageInfo = new HashMap<>();
    regionImageInfo.put("us-west-1", new ImageBundleDetails.BundleInfo());
    details.setRegions(regionImageInfo);
    details.setGlobalYbImage("yb_image");
    ImageBundle.create(p, "ib-1", details, true);

    Result providerRes = getProvider(p.getUuid());
    JsonNode bodyJson = (ObjectNode) Json.parse(contentAsString(providerRes));
    p = Json.fromJson(bodyJson, Provider.class);
    ImageBundle ib = new ImageBundle();
    ib.setName("ib-2");
    ib.setProvider(p);
    ib.setDetails(details);

    List<ImageBundle> ibs = p.getImageBundles();
    ibs.add(ib);
    p.setImageBundles(ibs);
    UUID taskUUID = doEditProvider(p, false);
    TaskInfo taskInfo = waitForTask(taskUUID);
    assertEquals(TaskInfo.State.Success, taskInfo.getTaskState());

    p = Provider.getOrBadRequest(p.getUuid());
    assertEquals(2, p.getImageBundles().size());
  }

  private Provider createK8sProvider() {
    return createK8sProvider(true);
  }

  private Provider createK8sProvider(boolean withConfig) {
    ObjectMapper mapper = new ObjectMapper();

    String providerName = "Kubernetes-Provider";
    ObjectNode bodyJson = Json.newObject();
    bodyJson.put("code", "kubernetes");
    bodyJson.put("name", providerName);
    ObjectNode configJson = Json.newObject();
    if (withConfig) {
      configJson.put("KUBECONFIG_NAME", "test");
      configJson.put("KUBECONFIG_CONTENT", "test");
    }
    configJson.put("KUBECONFIG_PROVIDER", "GKE");
    bodyJson.set("config", configJson);

    ArrayNode regions = mapper.createArrayNode();
    ObjectNode regionJson = Json.newObject();
    regionJson.put("code", "us-west1");
    regionJson.put("name", "US West");
    ArrayNode azs = mapper.createArrayNode();
    ObjectNode azJson = Json.newObject();
    azJson.put("code", "us-west1-a");
    azJson.put("name", "us-west1-a");
    azs.add(azJson);
    regionJson.putArray("zoneList").addAll(azs);
    regions.add(regionJson);
    bodyJson.putArray("regionList").addAll(regions);
    Result result =
        doRequestWithAuthTokenAndBody(
            "POST",
            "/api/customers/" + defaultCustomer.getUuid() + "/providers/kubernetes",
            user.createAuthToken(),
            bodyJson);
    JsonNode resultJson = Json.parse(contentAsString(result));

    return Provider.getOrBadRequest(
        defaultCustomer.getUuid(), UUID.fromString(resultJson.get("uuid").asText()));
  }

  private void mockDnsManagerListSuccess(String mockDnsName) {
    ShellResponse shellResponse = new ShellResponse();
    shellResponse.message = "{\"name\": \"" + mockDnsName + "\"}";
    shellResponse.code = 0;
    when(mockDnsManager.listDnsRecord(any(), any())).thenReturn(shellResponse);
  }

  private UUID doEditProvider(Provider editProviderReq, boolean validate) {
    return doEditProvider(editProviderReq, validate, false);
  }

  private UUID doEditProvider(
      Provider editProviderReq, boolean validate, boolean ignoreValidationErrors) {
    Result result = editProvider(editProviderReq, validate, ignoreValidationErrors);
    assertOk(result);
    JsonNode json = Json.parse(contentAsString(result));
    return UUID.fromString(json.get("taskUUID").asText());
  }

  private void verifyEditError(Provider provider, boolean validate, String error) {
    Result result = assertPlatformException(() -> editProvider(provider, validate));
    assertBadRequest(result, error);
  }
}
