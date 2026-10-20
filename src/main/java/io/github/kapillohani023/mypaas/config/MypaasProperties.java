package io.github.kapillohani023.mypaas.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mypaas")
public class MypaasProperties {

    private String dataDir = "./data";
    private String storageRoot = "./data/storage";
    private String keysDir = "./data/keys";
    private String workRoot = "./data/apps";
    private String managedNamespace = "example.com";
    private String publicBaseUrl = "http://localhost:8080";
    private int maxConcurrentDeployments = 3;
    private long maxUploadBytes = 50L * 1024 * 1024;
    private int logBufferSize = 5000;
    private int jobWorkerThreads = 3;
    private boolean jobWorkerEnabled = true;
    private boolean demoSeed = true;
    private String notificationsMode = "capture";
    private String mailFrom = "myPaaS <no-reply@mypaas.local>";
    private String localServerRoot = "./data/apps";
    private String bootstrapAdminEmail;
    private String bootstrapAdminPassword;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }
    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public String getKeysDir() { return keysDir; }
    public void setKeysDir(String keysDir) { this.keysDir = keysDir; }
    public String getWorkRoot() { return workRoot; }
    public void setWorkRoot(String workRoot) { this.workRoot = workRoot; }
    public String getManagedNamespace() { return managedNamespace; }
    public void setManagedNamespace(String managedNamespace) { this.managedNamespace = managedNamespace; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public int getMaxConcurrentDeployments() { return maxConcurrentDeployments; }
    public void setMaxConcurrentDeployments(int maxConcurrentDeployments) { this.maxConcurrentDeployments = maxConcurrentDeployments; }
    public long getMaxUploadBytes() { return maxUploadBytes; }
    public void setMaxUploadBytes(long maxUploadBytes) { this.maxUploadBytes = maxUploadBytes; }
    public int getLogBufferSize() { return logBufferSize; }
    public void setLogBufferSize(int logBufferSize) { this.logBufferSize = logBufferSize; }
    public int getJobWorkerThreads() { return jobWorkerThreads; }
    public void setJobWorkerThreads(int jobWorkerThreads) { this.jobWorkerThreads = jobWorkerThreads; }
    public boolean isJobWorkerEnabled() { return jobWorkerEnabled; }
    public void setJobWorkerEnabled(boolean jobWorkerEnabled) { this.jobWorkerEnabled = jobWorkerEnabled; }
    public boolean isDemoSeed() { return demoSeed; }
    public void setDemoSeed(boolean demoSeed) { this.demoSeed = demoSeed; }
    public String getNotificationsMode() { return notificationsMode; }
    public void setNotificationsMode(String notificationsMode) { this.notificationsMode = notificationsMode; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String mailFrom) { this.mailFrom = mailFrom; }
    public String getLocalServerRoot() { return localServerRoot; }
    public void setLocalServerRoot(String localServerRoot) { this.localServerRoot = localServerRoot; }
    public String getBootstrapAdminEmail() { return bootstrapAdminEmail; }
    public void setBootstrapAdminEmail(String bootstrapAdminEmail) { this.bootstrapAdminEmail = bootstrapAdminEmail; }
    public String getBootstrapAdminPassword() { return bootstrapAdminPassword; }
    public void setBootstrapAdminPassword(String bootstrapAdminPassword) { this.bootstrapAdminPassword = bootstrapAdminPassword; }
}
