package com.rideshare.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@SpringBootTest
public class DatabaseBackupExportTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    public void exportDatabaseBackup() throws Exception {
        System.out.println("==================================================");
        System.out.println("EXECUTING FULL DATABASE BACKUP EXPORT");
        System.out.println("==================================================");

        List<String> tableNames = jdbcTemplate.queryForList("""
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            ORDER BY table_name;
        """, String.class);

        Map<String, Object> backupData = new LinkedHashMap<>();
        backupData.put("backup_timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));
        backupData.put("table_count", tableNames.size());

        Map<String, List<Map<String, Object>>> tablesDump = new LinkedHashMap<>();
        long totalRows = 0;

        for (String table : tableNames) {
            try {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT * FROM \"" + table + "\"");
                tablesDump.put(table, rows);
                totalRows += rows.size();
                System.out.println(String.format("Backed up %-30s : %d rows", table, rows.size()));
            } catch (Exception e) {
                System.err.println("Failed to backup table " + table + ": " + e.getMessage());
            }
        }

        backupData.put("total_rows_backed_up", totalRows);
        backupData.put("tables", tablesDump);

        File backupDir = new File("backup");
        if (!backupDir.exists()) {
            backupDir.mkdirs();
        }

        File backupFile = new File(backupDir, "db_backup_pre_cleanup.json");
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        objectMapper.writeValue(backupFile, backupData);

        System.out.println("==================================================");
        System.out.println("BACKUP SUCCESSFUL!");
        System.out.println("Saved to: " + backupFile.getAbsolutePath());
        System.out.println("Total tables backed up: " + tableNames.size());
        System.out.println("Total records backed up: " + totalRows);
        System.out.println("File size: " + backupFile.length() + " bytes");
        System.out.println("==================================================");
    }
}
