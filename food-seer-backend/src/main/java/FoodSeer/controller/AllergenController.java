package FoodSeer.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import FoodSeer.service.AllergenService;

/**
 * Exposes the shared, site-wide allergen list - seeded with the original
 * fixed set, and extended by staff and admin.
 */
@CrossOrigin("*")
@RestController
@RequestMapping("/api/allergens")
public class AllergenController {

    @Autowired
    private AllergenService allergenService;

    @PreAuthorize("hasAnyRole('CUSTOMER', 'DRIVER', 'STAFF', 'ADMIN')")
    @GetMapping
    public List<String> getAllergens() {
        return allergenService.getAllAllergenNames();
    }

    /**
     * Adds a name to the shared allergen list that staff tag dishes with.
     * Staff and admin only; a customer's own custom allergy is saved on their
     * profile through /api/users/me/preferences.
     *
     * @param req
     *            body with "name"
     * @return the updated list, or 400 for a blank name
     */
    @PreAuthorize("hasAnyRole('STAFF', 'ADMIN')")
    @PostMapping
    public ResponseEntity<?> addAllergen(@RequestBody final Map<String, String> req) {
        try {
            final List<String> updated = allergenService.addAllergenIfNotExists(req.get("name"));
            return ResponseEntity.ok(updated);
        } catch (final IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
