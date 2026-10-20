package io.github.kapillohani023.mypaas.security;

/** Deployment origin. Derived from the security context, never a request parameter. */
public enum Trigger {
    DASHBOARD,
    API,
    WEBHOOK,
    SYSTEM
}
