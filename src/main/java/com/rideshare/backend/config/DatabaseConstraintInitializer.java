package com.rideshare.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Order(1)
public class DatabaseConstraintInitializer implements CommandLineRunner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            // Ensure columns exist on Supabase PostgreSQL
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS status VARCHAR(32) DEFAULT 'ACTIVE';");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(500);");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_at TIMESTAMP;");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS suspended_by VARCHAR(255);");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT NOW();");
            jdbcTemplate.execute("UPDATE users SET status = 'ACTIVE' WHERE status IS NULL;");
            jdbcTemplate.execute("UPDATE users SET created_at = NOW() WHERE created_at IS NULL;");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_users_role ON users (role);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);");

            jdbcTemplate.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS available_seats INTEGER DEFAULT 3;");
            jdbcTemplate.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS total_seats INTEGER DEFAULT 3;");
            jdbcTemplate.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS departure_time TIMESTAMP;");
            jdbcTemplate.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;");
            jdbcTemplate.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS completed_by VARCHAR(255);");

            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS payment_method VARCHAR(255) DEFAULT 'ONLINE';");
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS payment_status VARCHAR(255) DEFAULT 'PENDING';");
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS transaction_reference VARCHAR(255);");
            jdbcTemplate.execute("UPDATE bookings SET payment_method = 'ONLINE' WHERE payment_method IS NULL;");
            jdbcTemplate.execute("UPDATE bookings SET payment_status = 'PENDING' WHERE payment_status IS NULL;");

            // Migration: 12-digit alphanumeric booking_code
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS booking_code VARCHAR(32);");
            jdbcTemplate.execute("""
                UPDATE bookings 
                SET booking_code = 'BK' || upper(substr(md5(random()::text || id::text), 1, 10))
                WHERE booking_code IS NULL;
            """);
            jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_bookings_booking_code ON bookings (booking_code);");

            // Migration: Retroactive commission backfill for any completed cash rides
            jdbcTemplate.execute("""
                INSERT INTO commission_ledger (
                    driver_id,
                    booking_id,
                    ride_id,
                    entry_type,
                    gross_fare,
                    commission_rate_percent,
                    fixed_fee,
                    commission_amount,
                    driver_net_amount,
                    status,
                    idempotency_key,
                    created_at,
                    notes
                )
                SELECT 
                    r.driver_id,
                    b.id,
                    r.id,
                    'CHARGE',
                    b.total_amount,
                    15.0,
                    20.0,
                    ROUND((b.total_amount * 0.15 + 20.0)::numeric, 2),
                    ROUND((b.total_amount - (b.total_amount * 0.15 + 20.0))::numeric, 2),
                    'OUTSTANDING',
                    'COMM_CHARGE_BOOKING_' || b.id,
                    NOW(),
                    'Commission charge for cash booking ' || COALESCE(b.booking_code, '#' || b.id)
                FROM bookings b
                JOIN rides r ON b.ride_id = r.id
                WHERE (b.payment_status = 'PAID' OR b.status = 'COMPLETED')
                  AND r.driver_id IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM commission_ledger cl 
                      WHERE cl.booking_id = b.id AND cl.entry_type = 'CHARGE'
                  );
            """);

            // First clean up any historical duplicate active rides for the same driver if any exist
            jdbcTemplate.execute("""
                WITH active_ranked AS (
                    SELECT id, driver_id, status,
                           ROW_NUMBER() OVER (PARTITION BY driver_id ORDER BY requested_at DESC NULLS LAST, id DESC) as rn
                    FROM rides
                    WHERE status IN ('PUBLISHED', 'FULL', 'IN_PROGRESS', 'PENDING', 'ACCEPTED')
                      AND driver_id IS NOT NULL
                )
                UPDATE rides
                SET status = 'COMPLETED'
                WHERE id IN (
                    SELECT id FROM active_ranked WHERE rn > 1
                );
            """);

            // Office Carpooling Commute Preferences on users
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS home_address VARCHAR(255);");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS home_latitude DOUBLE PRECISION;");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS home_longitude DOUBLE PRECISION;");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS office_address VARCHAR(255);");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS office_latitude DOUBLE PRECISION;");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS office_longitude DOUBLE PRECISION;");
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS work_company VARCHAR(255);");

            // Zero-Commission Launch Mode Settings on platform_settings
            jdbcTemplate.execute("ALTER TABLE platform_settings ADD COLUMN IF NOT EXISTS commission_enabled BOOLEAN DEFAULT false;");
            jdbcTemplate.execute("ALTER TABLE platform_settings ADD COLUMN IF NOT EXISTS effective_date TIMESTAMP DEFAULT NOW();");
            jdbcTemplate.execute("UPDATE platform_settings SET commission_enabled = false WHERE commission_enabled IS NULL;");

            // Financial Snapshot columns on bookings
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS commission_rate_snapshot DOUBLE PRECISION DEFAULT 0.0;");
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS commission_fixed_fee_snapshot DOUBLE PRECISION DEFAULT 0.0;");
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS commission_enabled_snapshot BOOLEAN DEFAULT false;");
            jdbcTemplate.execute("ALTER TABLE bookings ADD COLUMN IF NOT EXISTS fee_type_snapshot VARCHAR(32) DEFAULT 'ZERO_COMMISSION';");

            // Recurring Office Carpooling Schedules Table
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS recurring_ride_schedules (
                    id BIGSERIAL PRIMARY KEY,
                    driver_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    origin VARCHAR(255) NOT NULL,
                    destination VARCHAR(255) NOT NULL,
                    origin_latitude DOUBLE PRECISION,
                    origin_longitude DOUBLE PRECISION,
                    destination_latitude DOUBLE PRECISION,
                    destination_longitude DOUBLE PRECISION,
                    days_of_week VARCHAR(100) NOT NULL,
                    departure_time VARCHAR(10) NOT NULL,
                    start_date DATE NOT NULL,
                    end_date DATE,
                    available_seats INTEGER NOT NULL DEFAULT 3,
                    total_seats INTEGER NOT NULL DEFAULT 3,
                    fare_per_seat DOUBLE PRECISION NOT NULL DEFAULT 50.0,
                    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
                    notes VARCHAR(500),
                    created_at TIMESTAMP DEFAULT NOW(),
                    updated_at TIMESTAMP DEFAULT NOW()
                );
            """);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_recurring_schedules_driver ON recurring_ride_schedules (driver_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_recurring_schedules_status ON recurring_ride_schedules (status);");

            // Link individual ride departures to recurring schedules
            jdbcTemplate.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS recurring_schedule_id BIGINT;");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rides_schedule_id ON rides (recurring_schedule_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rides_departure_time ON rides (departure_time);");

            // Upgrade constraint: Drivers cannot have multiple IN_PROGRESS trips simultaneously,
            // and cannot schedule duplicate rides for the exact same departure slot.
            jdbcTemplate.execute("DROP INDEX IF EXISTS idx_driver_active_ride;");
            jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_driver_active_in_progress
                ON rides (driver_id)
                WHERE status = 'IN_PROGRESS';
            """);
            jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_driver_scheduled_slot
                ON rides (driver_id, departure_time)
                WHERE status IN ('PUBLISHED', 'FULL', 'PENDING') AND departure_time IS NOT NULL;
            """);
            jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_driver_active_immediate
                ON rides (driver_id)
                WHERE status IN ('PUBLISHED', 'FULL', 'PENDING', 'ACCEPTED') AND departure_time IS NULL;
            """);

            // Driver presence table for real-time monitoring and heartbeat tracking
            jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS driver_presence (
                    driver_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                    last_heartbeat_at TIMESTAMP NOT NULL DEFAULT NOW(),
                    last_status VARCHAR(32) NOT NULL DEFAULT 'OFFLINE',
                    is_online BOOLEAN NOT NULL DEFAULT false,
                    current_ride_id BIGINT,
                    latitude DOUBLE PRECISION,
                    longitude DOUBLE PRECISION,
                    heading DOUBLE PRECISION,
                    speed DOUBLE PRECISION,
                    accuracy DOUBLE PRECISION,
                    location_updated_at TIMESTAMP,
                    location_sharing_enabled BOOLEAN NOT NULL DEFAULT true,
                    device_info VARCHAR(255),
                    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
                );
            """);

            jdbcTemplate.execute("ALTER TABLE driver_presence ALTER COLUMN location_sharing_enabled SET DEFAULT true;");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_driver_presence_heartbeat ON driver_presence (last_heartbeat_at);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_driver_presence_is_online ON driver_presence (is_online);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_driver_presence_location ON driver_presence (latitude, longitude) WHERE latitude IS NOT NULL;");

            // Backfill registered drivers into driver_presence if not present
            jdbcTemplate.execute("""
                INSERT INTO driver_presence (driver_id, last_heartbeat_at, last_status, is_online, location_sharing_enabled, updated_at)
                SELECT u.id, NOW() - INTERVAL '1 day', 'OFFLINE', false, true, NOW()
                FROM users u
                WHERE u.role = 'DRIVER'
                ON CONFLICT (driver_id) DO NOTHING;
            """);

            // Seed approved driver application for verified DRIVER accounts so they can publish rides
            jdbcTemplate.execute("""
                INSERT INTO driver_applications (
                    user_id, legal_name, contact_number, address, city, license_number, license_state, 
                    license_expiry_date, vehicle_type, vehicle_make, vehicle_model, vehicle_year, 
                    registration_number, vehicle_color, seat_capacity, status, submitted_at, reviewed_at
                )
                SELECT 
                    id, name, phone_number, 'Sector 29', 'New Delhi', 'DL-' || id || '-2026', 'DL', 
                    '2030-12-31', 'SEDAN', 'Maruti Suzuki', 'Dzire', '2023', 'DL-01-AB-' || (1000 + id), 
                    'White', 4, 'APPROVED', NOW() - INTERVAL '5 days', NOW() - INTERVAL '4 days'
                FROM users 
                WHERE role = 'DRIVER' 
                ON CONFLICT (user_id) DO UPDATE SET status = 'APPROVED' WHERE driver_applications.status IS NULL;
            """);

            // Ensure approved drivers also have their vehicle registered in vehicles table
            jdbcTemplate.execute("""
                INSERT INTO vehicles (driver_id, make, model, manufacture_year, license_plate, color)
                SELECT u.id, 'Maruti Suzuki', 'Dzire', '2023', 'DL-01-AB-' || (1000 + u.id), 'White'
                FROM users u
                WHERE u.role = 'DRIVER'
                AND NOT EXISTS (SELECT 1 FROM vehicles v WHERE v.driver_id = u.id);
            """);

            // Performance & Scalability Indexes
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rides_status ON rides (status);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rides_driver_id ON rides (driver_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_rides_rider_id ON rides (rider_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_bookings_passenger_id ON bookings (passenger_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_bookings_ride_id ON bookings (ride_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_bookings_status ON bookings (status);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_bookings_payment_status ON bookings (payment_status);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_commission_ledger_driver ON commission_ledger (driver_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_commission_ledger_entry_status ON commission_ledger (entry_type, status);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_user_documents_user ON user_documents (user_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_user_documents_status ON user_documents (verification_status);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_coupons_code ON coupons (code);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_coupon_redemptions_coupon ON coupon_redemptions (coupon_id);");
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_coupon_redemptions_user ON coupon_redemptions (user_id);");

            log.info(">>> DatabaseConstraintInitializer: Schema constraints, performance indexes, and initializations verified.");
        } catch (Exception e) {
            log.warn(">>> DatabaseConstraintInitializer warning: {}", e.getMessage());
        }
    }
}
