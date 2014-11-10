package com.puppetlabs.puppetserver;

public interface EnvironmentRegistry {
    public void registerEnvironment(String name);
    public boolean isExpired(String name);
    public void removeEnvironment(String name);
}
