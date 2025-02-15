/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.cn.dev;

import static org.apache.fineract.cn.accounting.api.v1.EventConstants.POST_ACCOUNT;
import static org.apache.fineract.cn.accounting.api.v1.EventConstants.POST_LEDGER;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import com.opentable.db.postgres.embedded.EmbeddedPostgres;
import org.apache.fineract.cn.accounting.api.v1.client.LedgerManager;
import org.apache.fineract.cn.accounting.importer.AccountImporter;
import org.apache.fineract.cn.accounting.importer.LedgerImporter;
import org.apache.fineract.cn.anubis.api.v1.domain.AllowedOperation;
import org.apache.fineract.cn.api.config.EnableApiFactory;
import org.apache.fineract.cn.api.context.AutoGuest;
import org.apache.fineract.cn.api.context.AutoSeshat;
import org.apache.fineract.cn.api.context.AutoUserContext;
import org.apache.fineract.cn.api.util.ApiConstants;
import org.apache.fineract.cn.api.util.ApiFactory;
import org.apache.fineract.cn.cassandra.util.CassandraConnectorConstants;
import org.apache.fineract.cn.cheque.api.v1.client.ChequeManager;
import org.apache.fineract.cn.customer.api.v1.CustomerEventConstants;
import org.apache.fineract.cn.customer.api.v1.client.CustomerManager;
import org.apache.fineract.cn.deposit.api.v1.client.DepositAccountManager;
import org.apache.fineract.cn.group.api.v1.client.GroupManager;
import org.apache.fineract.cn.identity.api.v1.client.IdentityManager;
import org.apache.fineract.cn.identity.api.v1.domain.Authentication;
import org.apache.fineract.cn.identity.api.v1.domain.Password;
import org.apache.fineract.cn.identity.api.v1.domain.Permission;
import org.apache.fineract.cn.identity.api.v1.domain.Role;
import org.apache.fineract.cn.identity.api.v1.domain.UserWithPassword;
import org.apache.fineract.cn.identity.api.v1.events.ApplicationPermissionEvent;
import org.apache.fineract.cn.identity.api.v1.events.ApplicationPermissionUserEvent;
import org.apache.fineract.cn.identity.api.v1.events.ApplicationSignatureEvent;
import org.apache.fineract.cn.identity.api.v1.events.EventConstants;
import org.apache.fineract.cn.lang.AutoTenantContext;
import org.apache.fineract.cn.postgresql.util.PostgreSQLConstants;
import org.apache.fineract.cn.notification.api.v1.client.NotificationManager;
import org.apache.fineract.cn.office.api.v1.client.OrganizationManager;
import org.apache.fineract.cn.payroll.api.v1.client.PayrollManager;
import org.apache.fineract.cn.portfolio.api.v1.client.PortfolioManager;
import org.apache.fineract.cn.provisioner.api.v1.client.Provisioner;
import org.apache.fineract.cn.provisioner.api.v1.domain.Application;
import org.apache.fineract.cn.provisioner.api.v1.domain.AssignedApplication;
import org.apache.fineract.cn.provisioner.api.v1.domain.AuthenticationResponse;
import org.apache.fineract.cn.provisioner.api.v1.domain.IdentityManagerInitialization;
import org.apache.fineract.cn.provisioner.api.v1.domain.Tenant;
import org.apache.fineract.cn.reporting.api.v1.client.ReportManager;
import org.apache.fineract.cn.rhythm.api.v1.client.RhythmManager;
import org.apache.fineract.cn.rhythm.api.v1.events.BeatEvent;
import org.apache.fineract.cn.teller.api.v1.client.TellerManager;
import org.apache.fineract.cn.test.env.ExtraProperties;
import org.apache.fineract.cn.test.fixture.postgresql.PostgreSQLInitializer;
import org.apache.fineract.cn.test.listener.EnableEventRecording;
import org.apache.fineract.cn.test.listener.EventRecorder;
import org.apache.fineract.cn.test.servicestarter.ActiveMQForTest;
import org.apache.fineract.cn.test.servicestarter.EurekaForTest;
import org.apache.fineract.cn.test.servicestarter.IntegrationTestEnvironment;
import org.apache.fineract.cn.test.servicestarter.Microservice;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.Base64Utils;

import java.util.ArrayList;

@SuppressWarnings("SpringAutowiredFieldsWarningInspection")
@RunWith(SpringRunner.class)
@SpringBootTest()
public class ServiceRunner {
  private static final String CLIENT_ID = "service-runner";
  private static final String SCHEDULER_USER_NAME = "imhotep";
  private static final String ADMIN_USER_NAME = "antony";
  private static final String TEST_LOGGER = "test-logger";
  private static final String LOAN_INCOME_LEDGER = "1100";

  private static Microservice<Provisioner> provisionerService;
  private static Microservice<IdentityManager> identityManager;
  private static Microservice<RhythmManager> rhythmManager;
  private static Microservice<OrganizationManager> organizationManager;
  private static Microservice<CustomerManager> customerManager;
  private static Microservice<LedgerManager> ledgerManager;
  private static Microservice<PortfolioManager> portfolioManager;
  private static Microservice<DepositAccountManager> depositAccountManager;
  private static Microservice<TellerManager> tellerManager;
  private static Microservice<ReportManager> reportManager;
  private static Microservice<ChequeManager> chequeManager;
  private static Microservice<PayrollManager> payrollManager;
  private static Microservice<GroupManager> groupManager;
  private static Microservice<NotificationManager> notificationManager;

  private static EmbeddedPostgres embeddedPostgres;

  private static final String CUSTOM_PROP_PREFIX = "custom.";
  private boolean runInDebug;

  @Configuration
  @ActiveMQForTest.EnableActiveMQListen
  @EnableApiFactory
  @EnableEventRecording(maxWait = 60_000)
  @ComponentScan("org.apache.fineract.cn.dev.listener")
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean(name = TEST_LOGGER)
    public Logger logger() {
      return LoggerFactory.getLogger(TEST_LOGGER);
    }
  }

  @ClassRule
  public static final EurekaForTest EUREKA_FOR_TEST = new EurekaForTest();

  @ClassRule
  public static final ActiveMQForTest ACTIVE_MQ_FOR_TEST = new ActiveMQForTest();

  @ClassRule
  public static final IntegrationTestEnvironment INTEGRATION_TEST_ENVIRONMENT = new IntegrationTestEnvironment("fineract-demo");

  @Autowired
  private ApiFactory apiFactory;

  @Autowired
  private EventRecorder eventRecorder;

  @Autowired
  private Environment environment;

  @Autowired
  protected DiscoveryClient discoveryClient;

  @Autowired
  @Qualifier(TEST_LOGGER)
  private Logger logger;

  private boolean isPersistent;
  private boolean shouldProvision;

  /* Enabling lite mode restricts the working set of micro-services to Provisioner, Identity, Rhythm, Organization and Customer
   */
  private boolean liteModeEnabled;
  private List<Tenant> tenantsToCreate;
  private AuthenticationResponse authenticationResponse;


  public ServiceRunner() {
    super();
  }

  @Before
  public void before() throws Exception {
    this.isPersistent = this.environment.containsProperty("demoserver.persistent");
    this.shouldProvision = this.environment.containsProperty("demoserver.provision");
    this.liteModeEnabled = this.environment.containsProperty("demoserver.lite");
    this.runInDebug = this.environment.containsProperty("demoserver.runInDebug");

    if (!this.isPersistent) {
      // start embedded Cassandra
      EmbeddedCassandraServerHelper.startEmbeddedCassandra(TimeUnit.SECONDS.toMillis(30L));
      // start embedded PostgreSQL
      ServiceRunner.embeddedPostgres = embeddedPostgres.builder().setPort(5432).start();
    }

    ExtraProperties generalProperties = new ExtraProperties();
    generalProperties.setProperty("server.max-http-header-size", Integer.toString(16 * 1024));
    generalProperties.setProperty("bonecp.partitionCount", "1");
    generalProperties.setProperty("bonecp.maxConnectionsPerPartition", "4");
    generalProperties.setProperty("bonecp.minConnectionsPerPartition", "1");
    generalProperties.setProperty("bonecp.acquireIncrement", "1");
    this.setAdditionalProperties(generalProperties);

    ServiceRunner.provisionerService = new Microservice<>(Provisioner.class, "provisioner", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    provisionerService.getProcessEnvironment().addSystemPrivateKeyToProperties();
    provisionerService.getProcessEnvironment().setProperty("system.initialclientid", ServiceRunner.CLIENT_ID);
    startService(generalProperties, provisionerService);

    // Creating Tenants before application startup to allow all microservices establish database connection
    // to the PostgreSQL Database
    if(this.shouldProvision){
      createTenants();
    }

    ServiceRunner.identityManager = new Microservice<>(IdentityManager.class, "identity", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT)
        .addProperties(new ExtraProperties() {{
          setProperty("identity.token.refresh.secureCookie", "false");
        }});
    startService(generalProperties, identityManager);

    ServiceRunner.rhythmManager = new Microservice<>(RhythmManager.class, "rhythm", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT)
        .addProperties(new ExtraProperties() {{
          setProperty("rhythm.beatCheckRate", Long.toString(TimeUnit.MINUTES.toMillis(10)));
          setProperty("rhythm.user", SCHEDULER_USER_NAME);
        }});
    startService(generalProperties, rhythmManager);

    ServiceRunner.organizationManager = new Microservice<>(OrganizationManager.class, "office", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    startService(generalProperties, organizationManager);

    ServiceRunner.customerManager = new Microservice<>(CustomerManager.class, "customer", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
    startService(generalProperties, customerManager);

    if (!liteModeEnabled) {
      ServiceRunner.ledgerManager = new Microservice<>(LedgerManager.class, "accounting", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ledgerManager);

      ServiceRunner.portfolioManager = new Microservice<>(PortfolioManager.class, "portfolio", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT)
          .addProperties(new ExtraProperties() {{
            setProperty("portfolio.bookLateFeesAndInterestAsUser", SCHEDULER_USER_NAME);
          }});
      startService(generalProperties, portfolioManager);

      ServiceRunner.depositAccountManager = new Microservice<>(DepositAccountManager.class, "deposit-account-management", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, depositAccountManager);

      ServiceRunner.tellerManager = new Microservice<>(TellerManager.class, "teller", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ServiceRunner.tellerManager);

      ServiceRunner.reportManager = new Microservice<>(ReportManager.class, "reporting", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ServiceRunner.reportManager);

      ServiceRunner.chequeManager = new Microservice<>(ChequeManager.class, "cheques", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ServiceRunner.chequeManager);

      ServiceRunner.payrollManager = new Microservice<>(PayrollManager.class, "payroll", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ServiceRunner.payrollManager);

      ServiceRunner.groupManager = new Microservice<>(GroupManager.class, "group", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ServiceRunner.groupManager);

      ServiceRunner.notificationManager = new Microservice<>(NotificationManager.class, "notification", "0.1.0-BUILD-SNAPSHOT", ServiceRunner.INTEGRATION_TEST_ENVIRONMENT);
      startService(generalProperties, ServiceRunner.notificationManager);
    }
  }

  @After
  public void tearDown() throws Exception {
    if (!liteModeEnabled) {
      ServiceRunner.notificationManager.kill();
      ServiceRunner.groupManager.kill();
      ServiceRunner.payrollManager.kill();
      ServiceRunner.chequeManager.kill();
      ServiceRunner.reportManager.kill();
      ServiceRunner.tellerManager.kill();
      ServiceRunner.depositAccountManager.kill();
      ServiceRunner.portfolioManager.kill();
      ServiceRunner.ledgerManager.kill();
    }
    ServiceRunner.customerManager.kill();
    ServiceRunner.organizationManager.kill();
    ServiceRunner.rhythmManager.kill();
    ServiceRunner.identityManager.kill();

    if (!isPersistent) {
      ServiceRunner.embeddedPostgres.close();
      EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
    }
  }

  @Test
  public void startDevServer() throws InterruptedException, IOException, ArtifactResolutionException {
    try {
      if (this.shouldProvision) {
        this.provisionAppsViaSeshat();
      } else {
        this.migrateServices();
      }
    } finally {
      ServiceRunner.provisionerService.kill();
    }

    System.out.println(identityManager.toString());
    System.out.println(organizationManager.toString());
    System.out.println(customerManager.toString());

    if (!liteModeEnabled) {
      System.out.println(ledgerManager.toString());
      System.out.println(portfolioManager.toString());
      System.out.println(depositAccountManager.toString());
      System.out.println(tellerManager.toString());
      System.out.println(reportManager.toString());
      System.out.println(chequeManager.toString());
      System.out.println(payrollManager.toString());
      System.out.println(groupManager.toString());
      System.out.println(notificationManager.toString());
    }

    boolean run = true;

    while (run) {
      final Scanner scanner = new Scanner(System.in);
      final String nextLine = scanner.nextLine();
      if (nextLine != null && nextLine.equals("exit")) {
        run = false;
      }
      eventRecorder.clear();
    }
  }

  private void startService(ExtraProperties properties, Microservice microservice) throws InterruptedException, IOException, ArtifactResolutionException {
    if (this.runInDebug) {
      microservice.runInDebug();
    }
    microservice.addProperties(properties);
    microservice.start();
    final boolean registered = microservice.waitTillRegistered(discoveryClient);
    logger.info("Service '{}' started and {} with Eureka.", microservice.name(), registered ? "registered" : "not registered");
    if (this.runInDebug) {
      logger.info("Service '{}' started with debug port {}.", microservice.name(), microservice.debuggingPort());
    }
    microservice.setApiFactory(this.apiFactory);
  }

  private void migrateServices() {
    final AuthenticationResponse authenticationResponse =
        ServiceRunner.provisionerService.api().authenticate(ServiceRunner.CLIENT_ID, ApiConstants.SYSTEM_SU, "oS/0IiAME/2unkN1momDrhAdNKOhGykYFH/mJN20");

    try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
      final List<Tenant> tenants = ServiceRunner.provisionerService.api().getTenants();
      tenants.forEach(tenant -> {
        final List<AssignedApplication> assignedApplications = ServiceRunner.provisionerService.api().getAssignedApplications(tenant.getIdentifier());
        assignedApplications.forEach(assignedApplication -> {
          if (assignedApplication.getName().equals(ServiceRunner.identityManager.name())) {
            ServiceRunner.provisionerService.api().assignIdentityManager(tenant.getIdentifier(), assignedApplication);
          } else {
            ServiceRunner.provisionerService.api().assignApplications(tenant.getIdentifier(), Collections.singletonList(assignedApplication));
          }
        });
      });
    }
  }

  private void provisionAppsViaSeshat() throws InterruptedException, IOException {
    final List<Application> applicationsToCreate = new ArrayList<>();
    applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.identityManager.name(), ServiceRunner.identityManager.uri()));
    applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.rhythmManager.name(), ServiceRunner.rhythmManager.uri()));
    applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.organizationManager.name(), ServiceRunner.organizationManager.uri()));
    applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.customerManager.name(), ServiceRunner.customerManager.uri()));

    if (!liteModeEnabled) {
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.ledgerManager.name(), ServiceRunner.ledgerManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.portfolioManager.name(), ServiceRunner.portfolioManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.depositAccountManager.name(), ServiceRunner.depositAccountManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.tellerManager.name(), ServiceRunner.tellerManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.reportManager.name(), ServiceRunner.reportManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.chequeManager.name(), ServiceRunner.chequeManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.payrollManager.name(), ServiceRunner.payrollManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.groupManager.name(), ServiceRunner.groupManager.uri()));
      applicationsToCreate.add(ApplicationBuilder.create(ServiceRunner.notificationManager.name(), ServiceRunner.notificationManager.uri()));
    }

    try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
      applicationsToCreate.forEach(application -> ServiceRunner.provisionerService.api().createApplication(application));
    }

    for (final Tenant tenant : tenantsToCreate) {
      try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
        provisionAppsViaSeshatForTenant(tenant);
      }
    }
  }

  private void createTenants() {
    this.authenticationResponse = ServiceRunner.provisionerService.api().authenticate(ServiceRunner.CLIENT_ID, ApiConstants.SYSTEM_SU, "oS/0IiAME/2unkN1momDrhAdNKOhGykYFH/mJN20");

    tenantsToCreate = Arrays.asList(
        TenantBuilder.create(ServiceRunner.provisionerService.getProcessEnvironment(), "playground", "A place to mess around and have fun", "playground")
        //TenantBuilder.create(ServiceRunner.provisionerService.getProcessEnvironment(), "demo-cccu", "Demo for CCCU", "demo_cccu"),
        //TenantBuilder.create(ServiceRunner.provisionerService.getProcessEnvironment(), "SKCUKNS1", "St Kitts Cooperative Credit Union", "SKCUKNS1"),
        //TenantBuilder.create(ServiceRunner.provisionerService.getProcessEnvironment(), "PCCUKNS1", "Police Cooperative Credit Union", "PCCUKNS1"),
        //TenantBuilder.create(ServiceRunner.provisionerService.getProcessEnvironment(), "FCCUKNS1", "FND Cooperative Credit Union", "FCCUKNS1"),
        //TenantBuilder.create(ServiceRunner.provisionerService.getProcessEnvironment(), "NCCUKNN1", "Nevis Cooperative Credit Union", "NCCUKNN1")
    );

    for (final Tenant tenant : tenantsToCreate) {
      try (final AutoSeshat ignored = new AutoSeshat(authenticationResponse.getToken())) {
        provisionerService.api().createTenant(tenant);
      }
    }
  }

  private String provisionAppsViaSeshatForTenant(final Tenant tenant) throws InterruptedException, IOException {

    try (final AutoTenantContext ignored = new AutoTenantContext(tenant.getIdentifier())) {

      final AssignedApplication isisAssigned = new AssignedApplication();
      isisAssigned.setName(identityManager.name());

      final IdentityManagerInitialization tenantAdminPassword = provisionerService.api().assignIdentityManager(tenant.getIdentifier(), isisAssigned);
      provisionApp(tenant, rhythmManager, org.apache.fineract.cn.rhythm.api.v1.events.EventConstants.INITIALIZE);
      provisionApp(tenant, ServiceRunner.organizationManager, org.apache.fineract.cn.office.api.v1.EventConstants.INITIALIZE);
      provisionApp(tenant, ServiceRunner.customerManager, CustomerEventConstants.INITIALIZE);

      final UserWithPassword orgAdminUserPassword = createOrgAdminRoleAndUser(tenantAdminPassword.getAdminPassword());

      //Creation of the schedulerUserRole, and permitting it to create application permission requests are needed in the
      //provisioning of portfolio.  Portfolio asks rhythm for a callback.  Rhythm asks identity for permission to send
      //that call back.  Rhythm needs permission to ask identity directly rather than through the provisioner because
      //the request is made outside of rhythm's initialization.

      if (!liteModeEnabled) {
        final UserWithPassword schedulerUser = createSchedulerUserRoleAndPassword(tenantAdminPassword.getAdminPassword());
        Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_APPLICATION_PERMISSION, new ApplicationPermissionEvent(rhythmManager.name(), org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT)));

        final Authentication schedulerUserAuthentication;
        try (final AutoGuest ignored2 = new AutoGuest()) {
          enableUser(schedulerUser);
          schedulerUserAuthentication = identityManager.api().login(schedulerUser.getIdentifier(), schedulerUser.getPassword());
        }

        try (final AutoUserContext ignored2 = new AutoUserContext(schedulerUser.getIdentifier(), schedulerUserAuthentication.getAccessToken())) {
          identityManager.api().setApplicationPermissionEnabledForUser(
              rhythmManager.name(),
              org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT,
              schedulerUser.getIdentifier(),
              true);
          Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_APPLICATION_PERMISSION_USER_ENABLED, new ApplicationPermissionUserEvent(rhythmManager.name(), org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT, schedulerUser.getIdentifier())));
        }

        provisionApp(tenant, ledgerManager, org.apache.fineract.cn.accounting.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, portfolioManager, org.apache.fineract.cn.portfolio.api.v1.events.EventConstants.INITIALIZE);

        Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_PERMITTABLE_GROUP,
            org.apache.fineract.cn.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioManager.name())));

        for (int i = 0; i < 24; i++) {
          Assert.assertTrue("Beat #" + i,
              eventRecorder.wait(org.apache.fineract.cn.rhythm.api.v1.events.EventConstants.POST_BEAT,
                  new BeatEvent(portfolioManager.name(), "alignment" + i)));
        }

        final Authentication schedulerAuthentication;
        try (final AutoGuest ignored2 = new AutoGuest()) {
          schedulerAuthentication = identityManager.api().login(schedulerUser.getIdentifier(), schedulerUser.getPassword());
        }

        try (final AutoUserContext ignored2 = new AutoUserContext(schedulerUser.getIdentifier(), schedulerAuthentication.getAccessToken())) {
          //Allow rhythm to send a beat to portfolio as the scheduler user.
          identityManager.api().setApplicationPermissionEnabledForUser(
              rhythmManager.name(),
              org.apache.fineract.cn.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioManager.name()),
              schedulerUser.getIdentifier(),
              true);
          Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_APPLICATION_PERMISSION_USER_ENABLED,
              new ApplicationPermissionUserEvent(rhythmManager.name(),
                  org.apache.fineract.cn.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioManager.name()), schedulerUser.getIdentifier())));
        }

        provisionApp(tenant, depositAccountManager, org.apache.fineract.cn.deposit.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, ServiceRunner.tellerManager, org.apache.fineract.cn.teller.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, ServiceRunner.reportManager, org.apache.fineract.cn.reporting.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, ServiceRunner.chequeManager, org.apache.fineract.cn.cheque.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, ServiceRunner.payrollManager, org.apache.fineract.cn.payroll.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, ServiceRunner.groupManager, org.apache.fineract.cn.group.api.v1.EventConstants.INITIALIZE);

        provisionApp(tenant, ServiceRunner.notificationManager, org.apache.fineract.cn.notification.api.v1.events.NotificationEventConstants.INITIALIZE);

        createChartOfAccounts(orgAdminUserPassword);
      }

      return tenantAdminPassword.getAdminPassword();
    }
  }

  private void createChartOfAccounts(final UserWithPassword userWithPassword) throws IOException, InterruptedException {
    final Authentication authentication;
    try (final AutoGuest ignored = new AutoGuest()) {
      authentication = identityManager.api().login(userWithPassword.getIdentifier(), userWithPassword.getPassword());
    }

    try (final AutoUserContext ignored = new AutoUserContext(userWithPassword.getIdentifier(), authentication.getAccessToken())) {
      final LedgerImporter ledgerImporter = new LedgerImporter(ledgerManager.api(), logger);
      final URL ledgersUrl = ServiceRunner.class.getResource("/standardChartOfAccounts/ledgers.csv");
      ledgerImporter.importCSV(ledgersUrl);
      Assert.assertTrue(this.eventRecorder.wait(POST_LEDGER, LOAN_INCOME_LEDGER));

      final AccountImporter accountImporter = new AccountImporter(ledgerManager.api(), logger);
      final URL accountsUrl = ServiceRunner.class.getResource("/standardChartOfAccounts/accounts.csv");
      accountImporter.importCSV(accountsUrl);
      Assert.assertTrue(this.eventRecorder.wait(POST_ACCOUNT, "9330"));

      identityManager.api().logout();
    }
  }

  private <T> void provisionApp(
      final Tenant tenant,
      final Microservice<T> service,
      final String initialize_event) throws InterruptedException {
    logger.info("Provisioning service '{}', for tenant '{}'.", service.name(), tenant.getName());

    final AssignedApplication assignedApp = new AssignedApplication();
    assignedApp.setName(service.name());

    provisionerService.api().assignApplications(tenant.getIdentifier(), Collections.singletonList(assignedApp));

    /*Assert.assertTrue(this.eventRecorder.wait(initialize_event, initialize_event));
    Assert.assertTrue(this.eventRecorder.waitForMatch(EventConstants.OPERATION_PUT_APPLICATION_SIGNATURE,
        (ApplicationSignatureEvent x) -> x.getApplicationIdentifier().equals(service.name())));*/
  }

  private UserWithPassword createSchedulerUserRoleAndPassword(String tenantAdminPassword) throws InterruptedException {
    final Authentication adminAuthentication;
    try (final AutoGuest ignored = new AutoGuest()) {
      adminAuthentication = identityManager.api().login(ADMIN_USER_NAME, tenantAdminPassword);
    }

    final UserWithPassword schedulerUser;
    try (final AutoUserContext ignored = new AutoUserContext(ADMIN_USER_NAME, adminAuthentication.getAccessToken())) {
      final Role schedulerRole = defineSchedulerRole();
      identityManager.api().createRole(schedulerRole);

      schedulerUser = new UserWithPassword();
      schedulerUser.setIdentifier(SCHEDULER_USER_NAME);
      schedulerUser.setPassword(encodePassword("26500BC"));
      schedulerUser.setRole(schedulerRole.getIdentifier());

      identityManager.api().createUser(schedulerUser);
      Assert.assertTrue(eventRecorder.wait(EventConstants.OPERATION_POST_USER, schedulerUser.getIdentifier()));
    }

    try (final AutoGuest ignored = new AutoGuest()) {
      enableUser(schedulerUser);
    }

    return schedulerUser;
  }

  private Role defineSchedulerRole() {
    final Permission permissionRequestionCreationPermission = new Permission();
    permissionRequestionCreationPermission.setAllowedOperations(Collections.singleton(AllowedOperation.CHANGE));
    permissionRequestionCreationPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.APPLICATION_SELF_MANAGEMENT);

    final Permission beatPublishToPortfolioPermission = new Permission();
    beatPublishToPortfolioPermission.setAllowedOperations(Collections.singleton(AllowedOperation.CHANGE));
    beatPublishToPortfolioPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.rhythm.spi.v1.PermittableGroupIds.forApplication(portfolioManager.name()));

    final Role role = new Role();
    role.setIdentifier("scheduler");
    role.setPermissions(Arrays.asList(permissionRequestionCreationPermission, beatPublishToPortfolioPermission));

    return role;
  }

  private UserWithPassword createOrgAdminRoleAndUser(final String tenantAdminPassword) throws InterruptedException {
    final Authentication adminAuthentication;
    try (final AutoUserContext ignored = new AutoGuest()) {
      adminAuthentication = ServiceRunner.identityManager.api().login(ADMIN_USER_NAME, tenantAdminPassword);
    }

    try (final AutoUserContext ignored = new AutoUserContext(ADMIN_USER_NAME, adminAuthentication.getAccessToken())) {
      final Role fimsAdministratorRole = defineOrgAdministratorRole();

      ServiceRunner.identityManager.api().createRole(fimsAdministratorRole);
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_ROLE, fimsAdministratorRole.getIdentifier()));

      final UserWithPassword fimsAdministratorUser = new UserWithPassword();
      fimsAdministratorUser.setIdentifier("operator");
      fimsAdministratorUser.setPassword(Base64Utils.encodeToString("init1@l".getBytes()));
      fimsAdministratorUser.setRole(fimsAdministratorRole.getIdentifier());

      ServiceRunner.identityManager.api().createUser(fimsAdministratorUser);
      Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_USER, fimsAdministratorUser.getIdentifier()));

      ServiceRunner.identityManager.api().logout();

      enableUser(fimsAdministratorUser);
      return fimsAdministratorUser;
    }
  }

  private Role defineOrgAdministratorRole() {
    final Permission employeeAllPermission = new Permission();
    employeeAllPermission.setAllowedOperations(AllowedOperation.ALL);
    employeeAllPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.office.api.v1.PermittableGroupIds.EMPLOYEE_MANAGEMENT);

    final Permission officeAllPermission = new Permission();
    officeAllPermission.setAllowedOperations(AllowedOperation.ALL);
    officeAllPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.office.api.v1.PermittableGroupIds.OFFICE_MANAGEMENT);

    final Permission userAllPermission = new Permission();
    userAllPermission.setAllowedOperations(AllowedOperation.ALL);
    userAllPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.IDENTITY_MANAGEMENT);

    final Permission roleAllPermission = new Permission();
    roleAllPermission.setAllowedOperations(AllowedOperation.ALL);
    roleAllPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.ROLE_MANAGEMENT);

    final Permission selfManagementPermission = new Permission();
    selfManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    selfManagementPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.identity.api.v1.PermittableGroupIds.SELF_MANAGEMENT);

    final Permission ledgerManagementPermission = new Permission();
    ledgerManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    ledgerManagementPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.accounting.api.v1.PermittableGroupIds.THOTH_LEDGER);

    final Permission accountManagementPermission = new Permission();
    accountManagementPermission.setAllowedOperations(AllowedOperation.ALL);
    accountManagementPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.accounting.api.v1.PermittableGroupIds.THOTH_ACCOUNT);

    final Permission customerPermission = new Permission();
    customerPermission.setAllowedOperations(Collections.singleton(AllowedOperation.READ));
    customerPermission.setPermittableEndpointGroupIdentifier(org.apache.fineract.cn.customer.PermittableGroupIds.CUSTOMER);

    final Role role = new Role();
    role.setIdentifier("orgadmin");
    role.setPermissions(
        Arrays.asList(
            employeeAllPermission,
            officeAllPermission,
            userAllPermission,
            roleAllPermission,
            selfManagementPermission,
            ledgerManagementPermission,
            accountManagementPermission,
            customerPermission
        )
    );

    return role;
  }

  private void enableUser(final UserWithPassword userWithPassword) throws InterruptedException {
    final Authentication passwordOnlyAuthentication
        = identityManager.api().login(userWithPassword.getIdentifier(), userWithPassword.getPassword());
    try (final AutoUserContext ignored
             = new AutoUserContext(userWithPassword.getIdentifier(), passwordOnlyAuthentication.getAccessToken())) {
      identityManager.api().changeUserPassword(
          userWithPassword.getIdentifier(), new Password(userWithPassword.getPassword()));
      Assert.assertTrue(eventRecorder.wait(EventConstants.OPERATION_PUT_USER_PASSWORD,
          userWithPassword.getIdentifier()));
    }
  }

  private static String encodePassword(final String password) {
    return Base64Utils.encodeToString(password.getBytes());
  }

  private void setAdditionalProperties(final ExtraProperties properties) {
    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + CassandraConnectorConstants.CONTACT_POINTS_PROP)) {
      properties.setProperty(CassandraConnectorConstants.CONTACT_POINTS_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + CassandraConnectorConstants.CONTACT_POINTS_PROP));
    }

    if (this.environment.containsProperty(CassandraConnectorConstants.CLUSTER_USER_PROP)) {
      properties.setProperty(CassandraConnectorConstants.CLUSTER_USER_PROP, this.environment.getProperty(CassandraConnectorConstants.CLUSTER_USER_PROP));
    }

    if (this.environment.containsProperty(CassandraConnectorConstants.CLUSTER_PASSWORD_PROP)) {
      properties.setProperty(CassandraConnectorConstants.CLUSTER_PASSWORD_PROP, this.environment.getProperty(CassandraConnectorConstants.CLUSTER_PASSWORD_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + PostgreSQLConstants.POSTGRESQL_HOST_PROP)) {
      properties.setProperty(PostgreSQLConstants.POSTGRESQL_HOST_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + PostgreSQLConstants.POSTGRESQL_HOST_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + PostgreSQLConstants.POSTGRESQL_USER_PROP)) {
      properties.setProperty(PostgreSQLConstants.POSTGRESQL_USER_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + PostgreSQLConstants.POSTGRESQL_USER_PROP));
    }

    if (this.environment.containsProperty(ServiceRunner.CUSTOM_PROP_PREFIX + PostgreSQLConstants.POSTGRESQL_PASSWORD_PROP)) {
      properties.setProperty(PostgreSQLConstants.POSTGRESQL_PASSWORD_PROP, this.environment.getProperty(ServiceRunner.CUSTOM_PROP_PREFIX + PostgreSQLConstants.POSTGRESQL_PASSWORD_PROP));
    }
  }
}
