package FoodSeer.repositories;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import FoodSeer.entity.Food;
import jakarta.persistence.LockModeType;

public interface FoodRepository extends JpaRepository<Food, Long> {

    /**
     * Loads a food and locks its row until the transaction ends, so two orders
     * placed at the same moment can't both take the last unit.
     *
     * @param id
     *            the food
     * @return the food, if it exists
     */
    @Lock ( LockModeType.PESSIMISTIC_WRITE )
    @Query ( "SELECT f FROM Food f WHERE f.id = :id" )
    Optional<Food> findByIdForUpdate ( @Param ( "id" ) Long id );

}
