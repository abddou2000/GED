package com.ipt.ged.notification;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PreferenceNotificationRepository extends JpaRepository<PreferenceNotification, UUID> {
}
