package io.github.kapillohani023.mypaas.authorization;

import io.github.kapillohani023.mypaas.security.Action;
import io.github.kapillohani023.mypaas.security.ResourceRef;

/** Delivery seam for OTP step-up challenges. Implemented by notification. */
public interface OtpSender {
    void sendStepUpOtp(String email, String displayName, String code, Action action, ResourceRef resource);
}
