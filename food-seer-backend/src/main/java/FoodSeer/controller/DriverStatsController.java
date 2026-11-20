package FoodSeer.controller;

import FoodSeer.service.DriverStatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/driverStats")
public class DriverStatsController {

    @Autowired
    DriverStatsService driverStatsService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or (hasRole('DRIVER') and authentication.name == #username)")
    public ResponseEntity<?> getDriverStats(@P("username") @RequestParam("username") String username) {

        // An unknown driver is a 404 from the service.
        return ResponseEntity.ok().body(driverStatsService.getDriverStats(username));
    }
}
