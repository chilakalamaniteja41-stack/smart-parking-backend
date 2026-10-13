package com.smartparking.smartparking;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
public class ParkingController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ParkingController(
            JdbcTemplate jdbc,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @GetMapping("/")
    public String home() {
        return "Smart Parking Java Backend is running!";
    }

    @GetMapping("/api/slots")
    public List<Map<String, Object>> getSlots() {

        return jdbc.query(
            """
            SELECT id, occupied, vehicleNumber
            FROM parking_slots
            ORDER BY CAST(SUBSTRING(id, 2) AS UNSIGNED)
            """,
            (rs, rowNum) -> {
                Map<String, Object> slot = new LinkedHashMap<>();

                String slotId = rs.getString("id");

                slot.put("id",
                    Integer.parseInt(slotId.substring(1)));
                slot.put("name", slotId);
                slot.put("status",
                    rs.getInt("occupied") == 1
                        ? "Occupied" : "Available");
                slot.put("vehicleNumber",
                    rs.getString("vehicleNumber"));

                return slot;
            }
        );
    }

    @GetMapping("/api/history")
    public List<Map<String, Object>> getHistory() {

        return jdbc.queryForList(
            """
            SELECT id, slotId, vehicleNumber, entryTime, exitTime
            FROM parking_history
            ORDER BY id DESC
            """
        );
    }

    @PatchMapping("/api/slots/{id}")
    public ResponseEntity<?> updateSlot(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {

        Matcher matcher = Pattern.compile("^P?(\\d+)$")
            .matcher(id.toUpperCase());

        if (!matcher.matches()) {
            return error(400, "Invalid slot ID.");
        }

        int number = Integer.parseInt(matcher.group(1));

        if (number < 1 || number > 10) {
            return error(400,
                "Slot number must be between 1 and 10.");
        }

        String slotId = "P" + String.format("%02d", number);

        String rawStatus = String.valueOf(
            body.getOrDefault("status", ""))
            .trim().toLowerCase();

        String status;

        if (rawStatus.equals("occupied")) {
            status = "Occupied";
        } else if (rawStatus.equals("available")) {
            status = "Available";
        } else {
            return error(400,
                "Status must be Available or Occupied.");
        }

        Object vehicleValue = body.containsKey("vehicleNumber")
            ? body.get("vehicleNumber")
            : body.get("vehicle_number");

        String vehicle = vehicleValue == null
            ? ""
            : vehicleValue.toString().trim().toUpperCase();

        if (status.equals("Occupied") && vehicle.isEmpty()) {
            return error(400, "Please enter a vehicle number.");
        }

        return transactions.execute(transactionStatus -> {
            List<Map<String, Object>> slots = jdbc.queryForList(
                """
                SELECT id, occupied, vehicleNumber
                FROM parking_slots
                WHERE id = ?
                FOR UPDATE
                """,
                slotId
            );

            if (slots.isEmpty()) {
                return error(404,
                    "Slot " + slotId
                    + " does not exist in the database.");
            }

            boolean occupied =
                ((Number) slots.get(0).get("occupied")).intValue() == 1;

            if (status.equals("Occupied")) {

                if (occupied) {
                    return error(409,
                        slotId + " is already occupied. Release it first.");
                }

                jdbc.update(
                    """
                    UPDATE parking_slots
                    SET occupied = 1, vehicleNumber = ?
                    WHERE id = ?
                    """,
                    vehicle, slotId
                );

                jdbc.update(
                    """
                    INSERT INTO parking_history
                    (slotId, vehicleNumber, entryTime, exitTime)
                    VALUES (?, ?, CURRENT_TIMESTAMP, NULL)
                    """,
                    slotId, vehicle
                );

            } else {

                if (occupied) {
                    jdbc.update(
                        """
                        UPDATE parking_history
                        SET exitTime = CURRENT_TIMESTAMP
                        WHERE slotId = ?
                          AND exitTime IS NULL
                        ORDER BY id DESC
                        LIMIT 1
                        """,
                        slotId
                    );
                }

                jdbc.update(
                    """
                    UPDATE parking_slots
                    SET occupied = 0, vehicleNumber = NULL
                    WHERE id = ?
                    """,
                    slotId
                );
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("message", slotId + " updated successfully.");
            result.put("id", number);
            result.put("name", slotId);
            result.put("status", status);
            result.put("vehicleNumber",
                status.equals("Occupied") ? vehicle : null);

            return ResponseEntity.ok(result);
        });
    }

    @PostMapping("/api/reset")
    public ResponseEntity<?> resetSlots() {

        return transactions.execute(transactionStatus -> {

            jdbc.update(
                """
                UPDATE parking_history h
                INNER JOIN parking_slots s ON h.slotId = s.id
                SET h.exitTime = CURRENT_TIMESTAMP
                WHERE s.occupied = 1
                  AND h.exitTime IS NULL
                """
            );

            jdbc.update(
                """
                UPDATE parking_slots
                SET occupied = 0, vehicleNumber = NULL
                """
            );

            return ResponseEntity.ok(Map.of(
                "message",
                "All parking slots are available. Parking history was preserved."
            ));
        });
    }

    private ResponseEntity<Map<String, String>> error(
            int code, String message) {

        return ResponseEntity.status(code)
            .body(Map.of("error", message));
    }
}