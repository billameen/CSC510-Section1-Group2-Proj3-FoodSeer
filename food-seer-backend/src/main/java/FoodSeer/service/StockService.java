package FoodSeer.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import FoodSeer.entity.Food;
import FoodSeer.entity.Order;
import FoodSeer.exception.ResourceNotFoundException;
import FoodSeer.repositories.FoodRepository;

/**
 * Stock moves in exactly two places: it is reserved when an order is placed
 * and released if the order is cancelled. Delivery doesn't touch it.
 */
@Service
public class StockService {

    private final FoodRepository foods;

    /**
     * @param foods
     *            food repository
     */
    public StockService ( final FoodRepository foods ) {
        this.foods = foods;
    }

    /**
     * Reserves stock for an order, all or nothing. Every line is checked
     * before any amount changes, and the whole call runs in the caller's
     * transaction, so a failure on the last item leaves every amount as it
     * was.
     *
     * @param foodIds
     *            one id per unit ordered (two coffees = the coffee id twice)
     * @return the foods, one entry per unit, in request order
     * @throws IllegalArgumentException
     *             (400) for an empty order, a missing id, or more than is in
     *             stock
     * @throws ResourceNotFoundException
     *             (404) for a food that doesn't exist
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public List<Food> reserve ( final List<Long> foodIds ) {
        if ( foodIds == null || foodIds.isEmpty() ) {
            throw new IllegalArgumentException( "An order needs at least one item." );
        }
        final Map<Long, Integer> wanted = new LinkedHashMap<>();
        for ( final Long id : foodIds ) {
            if ( id == null ) {
                throw new IllegalArgumentException( "Every item in an order needs a food id." );
            }
            wanted.merge( id, 1, Integer::sum );
        }

        // 1. Validate every line, locking each food row.
        final Map<Long, Food> loaded = new LinkedHashMap<>();
        for ( final Map.Entry<Long, Integer> line : wanted.entrySet() ) {
            final Food food = foods.findByIdForUpdate( line.getKey() ).orElseThrow(
                    () -> new ResourceNotFoundException( "Food " + line.getKey() + " does not exist." ) );
            if ( food.getAmount() < line.getValue() ) {
                throw new IllegalArgumentException( "Not enough " + food.getFoodName() + " in stock: you asked for "
                        + line.getValue() + ", " + food.getAmount() + " left." );
            }
            loaded.put( food.getId(), food );
        }

        // 2. Only then decrement.
        for ( final Map.Entry<Long, Integer> line : wanted.entrySet() ) {
            final Food food = loaded.get( line.getKey() );
            food.setAmount( food.getAmount() - line.getValue() );
            foods.save( food );
        }

        final List<Food> units = new ArrayList<>();
        for ( final Long id : foodIds ) {
            units.add( loaded.get( id ) );
        }
        return units;
    }

    /**
     * Puts an order's reserved stock back, for a cancelled order. A food that
     * was taken off the menu since still gets its units back.
     *
     * @param order
     *            the order being cancelled
     */
    @Transactional ( propagation = Propagation.MANDATORY )
    public void release ( final Order order ) {
        final Map<Long, Integer> counts = new LinkedHashMap<>();
        for ( final Food food : order.getFoods() ) {
            counts.merge( food.getId(), 1, Integer::sum );
        }
        for ( final Map.Entry<Long, Integer> line : counts.entrySet() ) {
            foods.findByIdForUpdate( line.getKey() ).ifPresent( food -> {
                food.setAmount( food.getAmount() + line.getValue() );
                foods.save( food );
            } );
        }
    }
}
