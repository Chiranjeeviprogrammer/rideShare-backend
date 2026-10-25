package com.rideshare.backend.service;

import com.rideshare.backend.config.AdminDriverMonitoringWebSocketHandler;
import com.rideshare.backend.config.DriverTrackingProperties;
import com.rideshare.backend.dto.DriverHeartbeatRequest;
import com.rideshare.backend.dto.DriverMonitoringItemDto;
import com.rideshare.backend.dto.DriverMonitoringMetricsDto;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.DriverPresence;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.DriverPresenceRepository;
import com.rideshare.backend.repository.RideRepository;
import com.rideshare.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DriverTrackingService {

    @Autowired
    private DriverPresenceRepository driverPresenceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private DriverTrackingProperties properties;

    @Autowired
    private AdminDriverMonitoringWebSocketHandler webSocketHandler;

    /**
     * Record authenticated driver heartbeat and optional location telemetry.
     */
    @Transactional
    public DriverPresence recordHeartbeat(User driver, DriverHeartbeatRequest request) {
        if (driver == null || !"DRIVER".equalsIgnoreCase(driver.getRole())) {
            throw new IllegalArgumentException("Only users with role DRIVER can report driver presence.");
        }

        LocalDateTime now = LocalDateTime.now();
        Long driverId = driver.getId();

        DriverPresence presence = driverPresenceRepository.findById(driverId)
                .orElse(DriverPresence.builder()
                        .driverId(driverId)
                        .lastHeartbeatAt(now)
                        .lastStatus("ONLINE")
                        .isOnline(true)
                        .locationSharingEnabled(true)
                        .updatedAt(now)
                        .build());

        presence.setLastHeartbeatAt(now);
        presence.setUpdatedAt(now);

        // Server-authoritative online status: A valid authenticated heartbeat marks driver ONLINE
        presence.setIsOnline(true);
        presence.setLastStatus("ONLINE");

        // Handle location data if provided and permitted
        boolean sharingEnabled = request == null || request.getLocationSharingEnabled() == null || Boolean.TRUE.equals(request.getLocationSharingEnabled());
        presence.setLocationSharingEnabled(sharingEnabled);

        if (sharingEnabled && request != null && request.getLatitude() != null && request.getLongitude() != null) {
            double lat = request.getLatitude();
            double lng = request.getLongitude();
            if (lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0) {
                presence.setLatitude(lat);
                presence.setLongitude(lng);
                presence.setLocationUpdatedAt(now);
                if (request.getHeading() != null) presence.setHeading(request.getHeading());
                if (request.getSpeed() != null) presence.setSpeed(request.getSpeed());
                if (request.getAccuracy() != null) presence.setAccuracy(request.getAccuracy());
            }
        }

        if (request != null && request.getDeviceInfo() != null && !request.getDeviceInfo().trim().isEmpty()) {
            presence.setDeviceInfo(request.getDeviceInfo().trim());
        }

        // Associate with any active ongoing ride
        Optional<Ride> activeRide = rideRepository.findFirstByDriverIdAndStatusInOrderByRequestedAtDesc(
                driverId, List.of("IN_PROGRESS", "ACCEPTED")
        );
        presence.setCurrentRideId(activeRide.map(Ride::getId).orElse(null));

        DriverPresence saved = driverPresenceRepository.save(presence);

        // Notify admins in real-time
        broadcastMetricsToAdmins();

        return saved;
    }

    /**
     * Mark authenticated driver offline explicitly (e.g. upon toggle, logout, or session end).
     */
    @Transactional
    public DriverPresence setDriverOffline(User driver) {
        if (driver == null || !"DRIVER".equalsIgnoreCase(driver.getRole())) {
            throw new IllegalArgumentException("Only users with role DRIVER can report driver presence.");
        }

        LocalDateTime now = LocalDateTime.now();
        Long driverId = driver.getId();

        DriverPresence presence = driverPresenceRepository.findById(driverId)
                .orElse(DriverPresence.builder()
                        .driverId(driverId)
                        .lastHeartbeatAt(now)
                        .build());

        presence.setIsOnline(false);
        presence.setLastStatus("OFFLINE");
        presence.setUpdatedAt(now);

        DriverPresence saved = driverPresenceRepository.save(presence);
        broadcastMetricsToAdmins();
        return saved;
    }

    /**
     * Retrieve the authenticated driver's presence record.
     */
    public DriverPresence getDriverPresence(User driver) {
        if (driver == null) return null;
        return driverPresenceRepository.findById(driver.getId()).orElse(null);
    }

    /**
     * Check if driver has an approved application.
     */
    public boolean isDriverApproved(Long driverId) {
        if (driverId == null) return false;
        Optional<DriverApplication> app = driverApplicationRepository.findByUserId(driverId);
        return app.isPresent() && "APPROVED".equalsIgnoreCase(app.get().getStatus());
    }

    /**
     * Calculate live metrics strictly from database state and server timestamps.
     */
    @Transactional(readOnly = true)
    public DriverMonitoringMetricsDto calculateMetrics() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime onlineThreshold = now.minusSeconds(properties.getOnlineTimeoutSeconds());
        LocalDateTime freshnessThreshold = now.minusSeconds(properties.getLocationFreshnessThresholdSeconds());

        List<User> drivers = userRepository.findByRole("DRIVER");
        long totalRegistered = drivers.size();

        if (totalRegistered == 0) {
            return DriverMonitoringMetricsDto.builder()
                    .totalRegisteredDrivers(0)
                    .onlineDrivers(0)
                    .availableDrivers(0)
                    .driversOnTrip(0)
                    .offlineDrivers(0)
                    .driversWithStaleLocation(0)
                    .lastUpdated(now)
                    .onlineTimeoutSeconds(properties.getOnlineTimeoutSeconds())
                    .locationFreshnessThresholdSeconds(properties.getLocationFreshnessThresholdSeconds())
                    .build();
        }

        List<Long> driverIds = drivers.stream().map(User::getId).collect(Collectors.toList());

        // Presence records
        Map<Long, DriverPresence> presenceMap = driverPresenceRepository.findAllById(driverIds)
                .stream().collect(Collectors.toMap(DriverPresence::getDriverId, Function.identity(), (a, b) -> a));

        // Driver applications
        Map<Long, DriverApplication> appMap = driverApplicationRepository.findAll().stream()
                .filter(a -> a.getUser() != null)
                .collect(Collectors.toMap(a -> a.getUser().getId(), Function.identity(), (a, b) -> a));

        // Active ongoing rides (IN_PROGRESS)
        List<Ride> inProgressRides = rideRepository.findByStatus("IN_PROGRESS");
        Set<Long> onTripDriverIds = inProgressRides.stream()
                .filter(r -> r.getDriver() != null)
                .map(r -> r.getDriver().getId())
                .collect(Collectors.toSet());

        long onlineCount = 0;
        long availableCount = 0;
        long onTripCount = 0;
        long offlineCount = 0;
        long staleLocationCount = 0;

        for (User driver : drivers) {
            DriverPresence p = presenceMap.get(driver.getId());
            if (p == null) {
                offlineCount++;
                continue;
            }
            boolean isOnline = Boolean.TRUE.equals(p.getIsOnline()) &&
                    p.getLastHeartbeatAt() != null &&
                    p.getLastHeartbeatAt().isAfter(onlineThreshold) &&
                    !"OFFLINE".equalsIgnoreCase(p.getLastStatus());

            if (!isOnline) {
                offlineCount++;
            } else {
                onlineCount++;

                boolean onTrip = onTripDriverIds.contains(driver.getId());
                if (onTrip) {
                    onTripCount++;
                } else {
                    DriverApplication app = appMap.get(driver.getId());
                    boolean isApproved = app != null && "APPROVED".equalsIgnoreCase(app.getStatus())
                            && !"SUSPENDED".equalsIgnoreCase(driver.getStatus());
                    if (isApproved) {
                        availableCount++;
                    }
                }

                // Check stale location
                boolean hasFreshLocation = p.getLatitude() != null
                        && p.getLongitude() != null
                        && p.getLocationUpdatedAt() != null
                        && p.getLocationUpdatedAt().isAfter(freshnessThreshold)
                        && Boolean.TRUE.equals(p.getLocationSharingEnabled());

                if (!hasFreshLocation) {
                    staleLocationCount++;
                }
            }
        }

        return DriverMonitoringMetricsDto.builder()
                .totalRegisteredDrivers(totalRegistered)
                .onlineDrivers(onlineCount)
                .availableDrivers(availableCount)
                .driversOnTrip(onTripCount)
                .offlineDrivers(offlineCount)
                .driversWithStaleLocation(staleLocationCount)
                .lastUpdated(now)
                .onlineTimeoutSeconds(properties.getOnlineTimeoutSeconds())
                .locationFreshnessThresholdSeconds(properties.getLocationFreshnessThresholdSeconds())
                .build();
    }

    /**
     * Search, filter, and paginate driver presence records for admin dashboard.
     */
    @Transactional(readOnly = true)
    public Page<DriverMonitoringItemDto> getDriverMonitoringList(String search, String filter, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime onlineThreshold = now.minusSeconds(properties.getOnlineTimeoutSeconds());
        LocalDateTime freshnessThreshold = now.minusSeconds(properties.getLocationFreshnessThresholdSeconds());

        List<User> allDrivers = userRepository.findByRole("DRIVER");

        if (allDrivers.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }

        List<Long> driverIds = allDrivers.stream().map(User::getId).collect(Collectors.toList());

        Map<Long, DriverPresence> presenceMap = driverPresenceRepository.findAllById(driverIds)
                .stream().collect(Collectors.toMap(DriverPresence::getDriverId, Function.identity(), (a, b) -> a));

        Map<Long, DriverApplication> appMap = driverApplicationRepository.findAll().stream()
                .filter(a -> a.getUser() != null)
                .collect(Collectors.toMap(a -> a.getUser().getId(), Function.identity(), (a, b) -> a));

        List<Ride> inProgressRides = rideRepository.findByStatus("IN_PROGRESS");
        Map<Long, Ride> onTripRidesMap = inProgressRides.stream()
                .filter(r -> r.getDriver() != null)
                .collect(Collectors.toMap(r -> r.getDriver().getId(), Function.identity(), (a, b) -> a));

        List<DriverMonitoringItemDto> items = new ArrayList<>();

        for (User driver : allDrivers) {
            // Apply text search
            if (search != null && !search.trim().isEmpty()) {
                String q = search.trim().toLowerCase();
                boolean matches = (driver.getName() != null && driver.getName().toLowerCase().contains(q)) ||
                        (driver.getEmail() != null && driver.getEmail().toLowerCase().contains(q)) ||
                        (driver.getPhoneNumber() != null && driver.getPhoneNumber().toLowerCase().contains(q)) ||
                        String.valueOf(driver.getId()).equals(q);
                if (!matches) {
                    continue;
                }
            }

            DriverPresence p = presenceMap.get(driver.getId());
            DriverApplication app = appMap.get(driver.getId());
            Ride currentRide = onTripRidesMap.get(driver.getId());

            boolean isOnline = p != null &&
                    Boolean.TRUE.equals(p.getIsOnline()) &&
                    p.getLastHeartbeatAt() != null &&
                    p.getLastHeartbeatAt().isAfter(onlineThreshold) &&
                    !"OFFLINE".equalsIgnoreCase(p.getLastStatus());

            String approvalStatus = (app != null && app.getStatus() != null) ? app.getStatus() : "NOT_APPLIED";
            boolean isApproved = "APPROVED".equalsIgnoreCase(approvalStatus) && !"SUSPENDED".equalsIgnoreCase(driver.getStatus());

            String presenceStatus = isOnline ? "ONLINE" : "OFFLINE";
            String availabilityStatus;

            if (isOnline) {
                if (currentRide != null) {
                    availabilityStatus = "ON_TRIP";
                } else if ("SUSPENDED".equalsIgnoreCase(driver.getStatus())) {
                    availabilityStatus = "UNAVAILABLE_SUSPENDED";
                } else if (!isApproved) {
                    availabilityStatus = "UNAVAILABLE_UNAPPROVED";
                } else {
                    availabilityStatus = "AVAILABLE";
                }
            } else {
                availabilityStatus = "OFFLINE";
            }

            boolean isLocationStale = true;
            Double lat = null;
            Double lng = null;
            Double heading = null;
            Double speed = null;
            LocalDateTime locationUpdatedAt = null;
            boolean locationSharingEnabled = p != null && Boolean.TRUE.equals(p.getLocationSharingEnabled());

            if (p != null) {
                locationUpdatedAt = p.getLocationUpdatedAt();
                heading = p.getHeading();
                speed = p.getSpeed();

                if (locationSharingEnabled && p.getLatitude() != null && p.getLongitude() != null) {
                    lat = p.getLatitude();
                    lng = p.getLongitude();
                    if (locationUpdatedAt != null && locationUpdatedAt.isAfter(freshnessThreshold)) {
                        isLocationStale = false;
                    }
                }
            }

            // Apply filter
            if (filter != null && !filter.trim().isEmpty() && !"ALL".equalsIgnoreCase(filter)) {
                String f = filter.trim().toUpperCase();
                switch (f) {
                    case "ONLINE":
                        if (!isOnline) continue;
                        break;
                    case "AVAILABLE":
                        if (!"AVAILABLE".equals(availabilityStatus)) continue;
                        break;
                    case "ON_TRIP":
                        if (!"ON_TRIP".equals(availabilityStatus)) continue;
                        break;
                    case "OFFLINE":
                        if (isOnline) continue;
                        break;
                    case "PENDING_APPROVAL":
                        if (isApproved) continue;
                        break;
                    case "STALE_LOCATION":
                        if (!isOnline || !isLocationStale) continue;
                        break;
                    default:
                        break;
                }
            }

            DriverMonitoringItemDto dto = DriverMonitoringItemDto.builder()
                    .driverId(driver.getId())
                    .driverName(driver.getName())
                    .driverEmail(driver.getEmail())
                    .driverPhone(driver.getPhoneNumber())
                    .avatarUrl(driver.getAvatarUrl())
                    .userStatus(driver.getStatus())
                    .approvalStatus(approvalStatus)
                    .isApproved(isApproved)
                    .isOnline(isOnline)
                    .presenceStatus(presenceStatus)
                    .availabilityStatus(availabilityStatus)
                    .currentRideId(currentRide != null ? currentRide.getId() : (p != null ? p.getCurrentRideId() : null))
                    .currentRidePickup(currentRide != null ? currentRide.getPickupLocation() : null)
                    .currentRideDropoff(currentRide != null ? currentRide.getDropoffLocation() : null)
                    .currentRideStatus(currentRide != null ? currentRide.getStatus() : null)
                    .lastHeartbeatAt(p != null ? p.getLastHeartbeatAt() : null)
                    .lastLocationUpdatedAt(locationUpdatedAt)
                    .latitude(lat)
                    .longitude(lng)
                    .heading(heading)
                    .speed(speed)
                    .isLocationStale(isLocationStale)
                    .locationSharingEnabled(locationSharingEnabled)
                    .deviceInfo(p != null ? p.getDeviceInfo() : null)
                    .build();

            items.add(dto);
        }

        // Sort items: ONLINE first, then AVAILABLE, then ON_TRIP, then by last heartbeat desc
        items.sort((a, b) -> {
            if (a.isOnline() != b.isOnline()) {
                return a.isOnline() ? -1 : 1;
            }
            if ("AVAILABLE".equals(a.getAvailabilityStatus()) && !"AVAILABLE".equals(b.getAvailabilityStatus())) {
                return -1;
            }
            if (!"AVAILABLE".equals(a.getAvailabilityStatus()) && "AVAILABLE".equals(b.getAvailabilityStatus())) {
                return 1;
            }
            if (a.getLastHeartbeatAt() != null && b.getLastHeartbeatAt() != null) {
                return b.getLastHeartbeatAt().compareTo(a.getLastHeartbeatAt());
            }
            return Long.compare(b.getDriverId(), a.getDriverId());
        });

        int total = items.size();
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), total);

        List<DriverMonitoringItemDto> pagedContent = (start <= total) ? items.subList(start, end) : Collections.emptyList();
        return new PageImpl<>(pagedContent, pageable, total);
    }

    /**
     * Periodic scheduled check marking stale drivers offline when heartbeats lapse.
     * Does NOT touch active rides!
     */
    @Scheduled(fixedRate = 10000)
    @Transactional
    public void checkPresenceExpiry() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime threshold = now.minusSeconds(properties.getOnlineTimeoutSeconds());

        int updated = driverPresenceRepository.markStaleDriversOffline(threshold, now);
        if (updated > 0) {
            broadcastMetricsToAdmins();
        }
    }

    /**
     * Push live metrics and active connection count to all listening admin WebSocket clients.
     */
    public void broadcastMetricsToAdmins() {
        try {
            DriverMonitoringMetricsDto metrics = calculateMetrics();
            Map<String, Object> message = Map.of(
                    "type", "METRICS_UPDATE",
                    "timestamp", LocalDateTime.now(),
                    "metrics", metrics
            );
            webSocketHandler.broadcastToAdmins(message);
        } catch (Exception e) {
            System.err.println("Failed to broadcast driver monitoring metrics: " + e.getMessage());
        }
    }
}
