package com.rideshare.backend.service;

import com.rideshare.backend.dto.RecurringRideScheduleRequest;
import com.rideshare.backend.model.Booking;
import com.rideshare.backend.model.DriverApplication;
import com.rideshare.backend.model.RecurringRideSchedule;
import com.rideshare.backend.model.Ride;
import com.rideshare.backend.model.User;
import com.rideshare.backend.repository.BookingRepository;
import com.rideshare.backend.repository.DriverApplicationRepository;
import com.rideshare.backend.repository.RecurringRideScheduleRepository;
import com.rideshare.backend.repository.RideRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RecurringRideService {

    public static final List<String> ALL_WEEKDAYS = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"
    );

    @Autowired
    private RecurringRideScheduleRepository scheduleRepository;

    @Autowired
    private RideRepository rideRepository;

    @Autowired
    private DriverApplicationRepository driverApplicationRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private NotificationService notificationService;

    /**
     * Create and activate a new recurring office commute schedule.
     * Enforces approved driver verification.
     */
    @Transactional
    public ResponseEntity<?> createSchedule(User driver, RecurringRideScheduleRequest request) {
        if (driver == null || !"DRIVER".equalsIgnoreCase(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only approved drivers can configure recurring office schedules."));
        }

        Optional<DriverApplication> appOpt = driverApplicationRepository.findByUserId(driver.getId());
        if (appOpt.isEmpty() || !"APPROVED".equalsIgnoreCase(appOpt.get().getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Driver verification pending: Your documents must be approved by an administrator before publishing recurring office rides."));
        }

        if (request.getOrigin() == null || request.getOrigin().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Pickup / origin address is required."));
        }
        if (request.getDestination() == null || request.getDestination().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Office / destination address is required."));
        }
        if (request.getDaysOfWeek() == null || request.getDaysOfWeek().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Select at least one weekday for recurring commute."));
        }

        // Validate time format (e.g. "08:30" or "08:30:00")
        if (request.getDepartureTime() == null || !request.getDepartureTime().matches("^([01]?[0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Valid departure time in 24-hr format (HH:mm or HH:mm:ss) is required."));
        }

        LocalDate startDate = request.getStartDate() != null ? request.getStartDate() : LocalDate.now();
        if (request.getEndDate() != null && request.getEndDate().isBefore(startDate)) {
            return ResponseEntity.badRequest().body(Map.of("message", "End date cannot be earlier than start date."));
        }

        // Clean & format days of week
        List<String> validDays = request.getDaysOfWeek().stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .filter(ALL_WEEKDAYS::contains)
                .distinct()
                .collect(Collectors.toList());

        if (validDays.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid days of week specified."));
        }

        int seats = (request.getAvailableSeats() != null && request.getAvailableSeats() > 0)
                ? request.getAvailableSeats()
                : (request.getTotalSeats() != null && request.getTotalSeats() > 0 ? request.getTotalSeats() : 3);
        double fare = (request.getFarePerSeat() != null && request.getFarePerSeat() >= 0)
                ? request.getFarePerSeat()
                : 50.0;

        RecurringRideSchedule schedule = RecurringRideSchedule.builder()
                .driver(driver)
                .origin(request.getOrigin().trim())
                .destination(request.getDestination().trim())
                .originLatitude(request.getOriginLatitude())
                .originLongitude(request.getOriginLongitude())
                .destinationLatitude(request.getDestinationLatitude())
                .destinationLongitude(request.getDestinationLongitude())
                .daysOfWeek(String.join(",", validDays))
                .departureTime(request.getDepartureTime().trim())
                .timezone(request.getTimezone() != null ? request.getTimezone().trim() : "Asia/Kolkata")
                .startDate(startDate)
                .endDate(request.getEndDate())
                .availableSeats(seats)
                .totalSeats(seats)
                .farePerSeat(fare)
                .status("ACTIVE")
                .notes(request.getNotes())
                .build();

        RecurringRideSchedule saved = scheduleRepository.save(schedule);

        // Instantly generate departure instances for the next 7 days
        int generatedCount = generateDeparturesForSchedule(saved, LocalDate.now(), LocalDate.now().plusDays(7));

        return ResponseEntity.ok(Map.of(
                "scheduleId", saved.getId(),
                "status", saved.getStatus(),
                "generatedDeparturesCount", generatedCount,
                "message", "Recurring office commute schedule created successfully with " + generatedCount + " upcoming departure(s) generated."
        ));
    }

    /**
     * Pause an active recurring schedule.
     */
    @Transactional
    public ResponseEntity<?> pauseSchedule(Long scheduleId, User driver) {
        Optional<RecurringRideSchedule> opt = scheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Recurring schedule not found."));
        }
        RecurringRideSchedule schedule = opt.get();
        if (!schedule.getDriver().getId().equals(driver.getId()) && !"ADMIN".equalsIgnoreCase(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Unauthorized to modify this schedule."));
        }

        schedule.setStatus("PAUSED");
        scheduleRepository.save(schedule);

        return ResponseEntity.ok(Map.of(
                "scheduleId", schedule.getId(),
                "status", "PAUSED",
                "message", "Recurring schedule paused. No new automatic departures will be generated."
        ));
    }

    /**
     * Resume a paused recurring schedule.
     */
    @Transactional
    public ResponseEntity<?> resumeSchedule(Long scheduleId, User driver) {
        Optional<RecurringRideSchedule> opt = scheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Recurring schedule not found."));
        }
        RecurringRideSchedule schedule = opt.get();
        if (!schedule.getDriver().getId().equals(driver.getId()) && !"ADMIN".equalsIgnoreCase(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Unauthorized to modify this schedule."));
        }

        schedule.setStatus("ACTIVE");
        scheduleRepository.save(schedule);

        int generated = generateDeparturesForSchedule(schedule, LocalDate.now(), LocalDate.now().plusDays(7));

        return ResponseEntity.ok(Map.of(
                "scheduleId", schedule.getId(),
                "status", "ACTIVE",
                "generatedDeparturesCount", generated,
                "message", "Recurring schedule resumed with " + generated + " new departure(s) generated."
        ));
    }

    /**
     * Cancel recurring schedule: cancels future uncompleted rides and notifies any booked passengers.
     * Preserves completed historical rides without alteration.
     */
    @Transactional
    public ResponseEntity<?> cancelSchedule(Long scheduleId, User driver, String cancelReason) {
        Optional<RecurringRideSchedule> opt = scheduleRepository.findById(scheduleId);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Recurring schedule not found."));
        }
        RecurringRideSchedule schedule = opt.get();
        if (!schedule.getDriver().getId().equals(driver.getId()) && !"ADMIN".equalsIgnoreCase(driver.getRole())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Unauthorized to modify this schedule."));
        }

        schedule.setStatus("CANCELLED");
        scheduleRepository.save(schedule);

        LocalDateTime now = LocalDateTime.now();
        List<Ride> futureRides = rideRepository.findByRecurringScheduleId(scheduleId).stream()
                .filter(r -> r.getDepartureTime() != null && r.getDepartureTime().isAfter(now))
                .filter(r -> "PUBLISHED".equalsIgnoreCase(r.getStatus()) || "FULL".equalsIgnoreCase(r.getStatus()))
                .collect(Collectors.toList());

        int cancelledDepartures = 0;
        int notifiedPassengers = 0;

        for (Ride ride : futureRides) {
            ride.setStatus("CANCELLED");
            ride.setCompletedAt(now);
            ride.setCompletedBy("DRIVER_RECURRING_CANCEL");
            rideRepository.save(ride);
            cancelledDepartures++;

            // Cancel any passenger bookings and notify them
            List<Booking> bookings = bookingRepository.findByRideId(ride.getId());
            for (Booking b : bookings) {
                if ("CONFIRMED".equalsIgnoreCase(b.getStatus()) || "PENDING".equalsIgnoreCase(b.getStatus())) {
                    b.setStatus("CANCELLED");
                    b.setPaymentStatus("REFUND_INITIATED");
                    b.setUpdatedAt(now);
                    bookingRepository.save(b);

                    if (b.getPassenger() != null) {
                        notificationService.sendNotification(
                                b.getPassenger(),
                                "Office Commute Ride Cancelled",
                                "Your scheduled office ride on " + ride.getDepartureTime() + " was cancelled by the driver."
                        );
                        notifiedPassengers++;
                    }
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "scheduleId", schedule.getId(),
                "status", "CANCELLED",
                "futureDeparturesCancelled", cancelledDepartures,
                "passengersNotified", notifiedPassengers,
                "message", "Recurring schedule cancelled successfully."
        ));
    }

    /**
     * List all recurring schedules configured by authenticated driver.
     */
    public List<RecurringRideSchedule> getDriverSchedules(Long driverId) {
        return scheduleRepository.findByDriverId(driverId);
    }

    /**
     * List all departures associated with a specific schedule.
     */
    public List<Ride> getScheduleDepartures(Long scheduleId) {
        return rideRepository.findByRecurringScheduleId(scheduleId);
    }

    /**
     * Automated server-side generator for recurring office commute departures.
     * Scheduled every day at 01:00 AM server time (and can be invoked on-demand).
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void scheduleDailyDepartureGenerator() {
        log.info("Starting automated recurring office departure generator...");
        LocalDate today = LocalDate.now();
        LocalDate windowEnd = today.plusDays(10); // maintain 10-day rolling departure horizon

        List<RecurringRideSchedule> activeSchedules = scheduleRepository.findByStatus("ACTIVE");
        int totalGenerated = 0;

        for (RecurringRideSchedule schedule : activeSchedules) {
            try {
                totalGenerated += generateDeparturesForSchedule(schedule, today, windowEnd);
            } catch (Exception e) {
                log.error("Error generating departures for schedule ID {}: {}", schedule.getId(), e.getMessage());
            }
        }
        log.info("Automated departure generator finished: {} new departure(s) created across {} active schedule(s).",
                totalGenerated, activeSchedules.size());
    }

    /**
     * Idempotent departure generator for a specific schedule between startDate and endDate.
     * Prevents duplicate departures for the same departure time.
     */
    @Transactional
    public int generateDeparturesForSchedule(RecurringRideSchedule schedule, LocalDate fromDate, LocalDate toDate) {
        if (!"ACTIVE".equalsIgnoreCase(schedule.getStatus())) {
            return 0;
        }

        Set<String> activeDays = Arrays.stream(schedule.getDaysOfWeek().split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(Collectors.toSet());

        LocalTime departureTime = LocalTime.parse(schedule.getDepartureTime());
        LocalDateTime now = LocalDateTime.now();

        LocalDate effectiveStart = schedule.getStartDate().isAfter(fromDate) ? schedule.getStartDate() : fromDate;
        LocalDate effectiveEnd = schedule.getEndDate() != null && schedule.getEndDate().isBefore(toDate)
                ? schedule.getEndDate()
                : toDate;

        if (effectiveStart.isAfter(effectiveEnd)) {
            return 0;
        }

        int count = 0;
        LocalDate cursor = effectiveStart;

        while (!cursor.isAfter(effectiveEnd)) {
            DayOfWeek dow = cursor.getDayOfWeek();
            if (activeDays.contains(dow.name())) {
                LocalDateTime departureDateTime = cursor.atTime(departureTime);

                // Only schedule future departures
                if (departureDateTime.isAfter(now)) {
                    // Check if driver already has an active, full, or in-progress ride scheduled for this exact departure slot
                    boolean slotTaken = rideRepository.existsByDriverIdAndDepartureTimeAndStatusIn(
                            schedule.getDriver().getId(),
                            departureDateTime,
                            List.of("PUBLISHED", "FULL", "IN_PROGRESS", "ACCEPTED")
                    );

                    if (!slotTaken) {
                        Ride departure = new Ride();
                        departure.setDriver(schedule.getDriver());
                        departure.setRider(schedule.getDriver());
                        departure.setPickupLocation(schedule.getOrigin());
                        departure.setDropoffLocation(schedule.getDestination());
                        departure.setPickupLatitude(schedule.getOriginLatitude());
                        departure.setPickupLongitude(schedule.getOriginLongitude());
                        departure.setDropoffLatitude(schedule.getDestinationLatitude());
                        departure.setDropoffLongitude(schedule.getDestinationLongitude());
                        departure.setFare(schedule.getFarePerSeat());
                        departure.setAvailableSeats(schedule.getAvailableSeats());
                        departure.setTotalSeats(schedule.getTotalSeats());
                        departure.setStatus("PUBLISHED");
                        departure.setDepartureTime(departureDateTime);
                        departure.setRequestedAt(now);
                        departure.setRecurringScheduleId(schedule.getId());

                        rideRepository.save(departure);
                        count++;
                    }
                }
            }
            cursor = cursor.plusDays(1);
        }

        return count;
    }
}
