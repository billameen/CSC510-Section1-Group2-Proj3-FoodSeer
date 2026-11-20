package FoodSeer.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import FoodSeer.constant.UserRole;
import FoodSeer.entity.DriverStats;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.repositories.DriverStatsRepository;
import FoodSeer.entity.User;
import FoodSeer.repositories.UserRepository;
import FoodSeer.service.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private UserRepository userRepository;

    // gives a user promoted to driver the stats row the driver dashboard reads
    @Autowired
    private DriverStatsRepository driverStatsRepository;

    @Override
    public User getCurrentUser () {
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if ( auth == null || !auth.isAuthenticated() ) {
            return null;
        }

        final String username = auth.getName();
        final Optional<User> u = userRepository.findByUsername( username );
        return u.orElse( null );
    }

    @Override
    public java.util.List<User> listUsers() {
        return userRepository.findByActiveTrue();
    }

    @Override
    public User findById(final Long id) {
        return userRepository.findById(id).filter(User::isActive).orElse(null);
    }

    /**
     * Changes a user's role. The role is parsed, so an empty or invented role
     * is a 400. Demoting the last admin is a 409, since nobody could manage
     * users afterwards. A user who becomes a driver gets a driver-stats row,
     * which the driver dashboard needs.
     */
    @Override
    public User updateUserRole(final Long id, final String role) {
        final UserRole newRole = UserRole.parse(role);
        final Optional<User> u = userRepository.findById(id).filter(User::isActive);
        if (u.isEmpty()) return null;
        final User user = u.get();
        if (isLastAdmin(user) && newRole != UserRole.ADMIN) {
            throw new IllegalStateException("You cannot remove the last admin. Make someone else an admin first.");
        }
        user.setRole(newRole.authority());
        if (newRole == UserRole.DRIVER && driverStatsRepository.findByUsername(user.getUsername()).isEmpty()) {
            final DriverStats stats = new DriverStats();
            stats.setUsername(user.getUsername());
            driverStatsRepository.save(stats);
        }
        return userRepository.save(user);
    }

    /**
     * Deletes a user's account. It is a soft delete: the account is
     * deactivated (no login, not listed) but its orders and meal logs stay, so
     * order history and allergy trace-back over those orders keep working. An
     * admin can't delete their own account, and nobody can delete the last
     * admin (409 for both), so the team can't lock itself out.
     */
    @Override
    public void deleteUser(final Long id) {
        final User user = userRepository.findById(id).filter(User::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("User does not exist with id " + id));

        final User caller = getCurrentUser();
        if (caller != null && caller.getId() == user.getId()) {
            throw new IllegalStateException("You cannot delete your own account.");
        }
        if (isLastAdmin(user)) {
            throw new IllegalStateException("You cannot delete the last admin.");
        }

        user.setActive(false);
        userRepository.save(user);
    }

    private boolean isLastAdmin(final User user) {
        return UserRole.ADMIN.authority().equals(user.getRole())
                && userRepository.countByRoleAndActiveTrue(UserRole.ADMIN.authority()) <= 1;
    }

    @Override
    public User getByUsername(String username) {
        final Optional<User> u = userRepository.findByUsername(username);
        return u.orElse(null);
    }

    @Override
    // signature extended with a 4th parameter, dailyCalorieGoal
    public User updateUserPreferences(String username, String costPreference, String dietaryRestrictions, Integer dailyCalorieGoal) {
        final Optional<User> u = userRepository.findByUsername(username);
        if (u.isEmpty()) return null;
        final User user = u.get();
        user.setCostPreference(costPreference);
        user.setDietaryRestrictions(dietaryRestrictions);
        // Partial update: a request that omits dailyCalorieGoal (null) must not
        // wipe out a previously-set goal, since the existing frontend preferences
        // form does not send this field at all.
        // only overwrite the goal when a real value was sent, unlike cost/dietary above
        if (dailyCalorieGoal != null) {
            user.setDailyCalorieGoal(dailyCalorieGoal);
        }
        return userRepository.save(user);
    }
}
