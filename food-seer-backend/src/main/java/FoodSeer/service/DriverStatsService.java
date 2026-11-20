package FoodSeer.service;

import FoodSeer.dto.DriverStatsDto;

import java.math.BigDecimal;

public interface DriverStatsService {

    /**
     * @param username the driver
     * @return their statistics
     * @throws FoodSeer.exception.ResourceNotFoundException if there are none (404)
     */
    DriverStatsDto getDriverStats(String username);
    void updateTotalEarnings(String username, BigDecimal earnings);
}
