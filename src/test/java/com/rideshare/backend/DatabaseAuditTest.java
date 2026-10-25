package com.rideshare.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

@SpringBootTest
public class DatabaseAuditTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void auditDatabaseTablesAndData() {
        System.out.println("==================================================");
        System.out.println("DATABASE AUDIT REPORT");
        System.out.println("==================================================");

        // 1. List all public tables
        List<Map<String, Object>> tables = jdbcTemplate.queryForList("""
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            ORDER BY table_name;
        """);

        System.out.println("\n--- TABLE ROW COUNTS ---");
        for (Map<String, Object> t : tables) {
            String tableName = (String) t.get("table_name");
            try {
                Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"" + tableName + "\"", Long.class);
                System.out.println(String.format("%-32s : %d rows", tableName, count));
            } catch (Exception e) {
                System.out.println(String.format("%-32s : ERROR (%s)", tableName, e.getMessage()));
            }
        }

        // 2. Foreign keys and dependencies
        System.out.println("\n--- FOREIGN KEY DEPENDENCIES ---");
        List<Map<String, Object>> fks = jdbcTemplate.queryForList("""
            SELECT
                tc.table_name, 
                kcu.column_name, 
                ccu.table_name AS foreign_table_name,
                ccu.column_name AS foreign_column_name,
                rc.delete_rule
            FROM 
                information_schema.table_constraints AS tc 
                JOIN information_schema.key_column_usage AS kcu
                  ON tc.constraint_name = kcu.constraint_name
                  AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage AS ccu
                  ON ccu.constraint_name = tc.constraint_name
                  AND ccu.table_schema = tc.table_schema
                JOIN information_schema.referential_constraints AS rc
                  ON tc.constraint_name = rc.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public'
            ORDER BY tc.table_name, kcu.column_name;
        """);

        for (Map<String, Object> fk : fks) {
            System.out.println(String.format("%s.%s -> %s.%s (ON DELETE %s)",
                    fk.get("table_name"), fk.get("column_name"),
                    fk.get("foreign_table_name"), fk.get("foreign_column_name"),
                    fk.get("delete_rule")));
        }

        // 3. Inspect Car-related tables
        System.out.println("\n--- CAR-RELATED DATA INSPECTION ---");
        try {
            Long carMakesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM car_makes", Long.class);
            System.out.println("car_makes count: " + carMakesCount);
            List<Map<String, Object>> makes = jdbcTemplate.queryForList("SELECT id, name, logo_url FROM car_makes LIMIT 10");
            for (Map<String, Object> m : makes) {
                System.out.println("  Make: " + m);
            }

            Long carModelsCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM car_models", Long.class);
            System.out.println("car_models count: " + carModelsCount);
            List<Map<String, Object>> models = jdbcTemplate.queryForList("SELECT id, name, make_id FROM car_models LIMIT 10");
            for (Map<String, Object> m : models) {
                System.out.println("  Model: " + m);
            }

            Long vehiclesCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vehicles", Long.class);
            System.out.println("vehicles count: " + vehiclesCount);
            List<Map<String, Object>> vehicles = jdbcTemplate.queryForList("SELECT id, make, model, license_plate, driver_id FROM vehicles LIMIT 10");
            for (Map<String, Object> v : vehicles) {
                System.out.println("  Vehicle: " + v);
            }
        } catch (Exception e) {
            System.out.println("Error inspecting car data: " + e.getMessage());
        }

        // 4. Users summary by role
        System.out.println("\n--- USERS SUMMARY BY ROLE ---");
        try {
            List<Map<String, Object>> userRoles = jdbcTemplate.queryForList("SELECT role, COUNT(*) as cnt FROM users GROUP BY role");
            for (Map<String, Object> ur : userRoles) {
                System.out.println("  Role: " + ur.get("role") + " -> " + ur.get("cnt"));
            }

            // Check if fixed accounts already exist
            List<Map<String, Object>> fixedUsers = jdbcTemplate.queryForList("""
                SELECT id, email, name, role, status 
                FROM users 
                WHERE lower(email) IN ('admin@rideshare.com', 'support@rideshare.com', 'driver@rideshare.com', 'rider@rideshare.com')
            """);
            System.out.println("\nExisting fixed test accounts in DB:");
            for (Map<String, Object> fu : fixedUsers) {
                System.out.println("  " + fu);
            }
        } catch (Exception e) {
            System.out.println("Error inspecting users: " + e.getMessage());
        }
    }
}
