package FoodSeer.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An order in API responses. It carries plain values and nested records only,
 * never entities: the items are OrderLineDto snapshots and the driver is just
 * a username. Placing an order uses NewOrderRequest instead.
 */
public class OrderDto {

    /** Order ID */
    private Long id;

    /** Order name */
    private String name;

    /** Each unit as it was when the order was placed */
    private List<OrderLineDto> foods;

    /** True once delivered; derived from the status */
    private boolean isFulfilled;

    private BigDecimal cost;

    private String status;

    private BigDecimal rating;

    /** The assigned driver's username, or null before a driver claims it */
    private String driverUsername;

    /** Who placed it */
    private String customerUsername;

    private BigDecimal deliveryCost;

    private LocalDateTime createdAt;

    private String allergyRestrictions;

    /** Foods in this order the customer already rated (one rating per dish per order) */
    private Set<Long> ratedFoodIds;

    /**
     * Default constructor for OrderDto.
     */
    public OrderDto() {
        this.foods = new ArrayList<>();
        this.ratedFoodIds = new HashSet<>();
    }

    /**
     * Creates an OrderDto with the given ID and name.
     *
     * @param id   the order ID
     * @param name the order name
     */
    public OrderDto(final Long id, final String name) {
        this();
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(final Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public List<OrderLineDto> getFoods() {
        return foods;
    }

    public void setFoods(final List<OrderLineDto> foods) {
        this.foods = foods;
    }

    public boolean getIsFulfilled() {
        return isFulfilled;
    }

    public void setIsFulfilled(final boolean isFulfilled) {
        this.isFulfilled = isFulfilled;
    }

    public Set<Long> getRatedFoodIds() {
        return ratedFoodIds;
    }

    public void setRatedFoodIds(final Set<Long> ratedFoodIds) {
        this.ratedFoodIds = ratedFoodIds;
    }

    public BigDecimal getCost() {
        return cost;
    }

    public void setCost(final BigDecimal cost) {
        this.cost = cost;
    }

    public BigDecimal getRating() {
        return rating;
    }

    public void setRating(final BigDecimal rating) {
        this.rating = rating;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getDriverUsername() {
        return driverUsername;
    }

    public void setDriverUsername(final String driverUsername) {
        this.driverUsername = driverUsername;
    }

    public String getCustomerUsername() {
        return customerUsername;
    }

    public void setCustomerUsername(final String customerUsername) {
        this.customerUsername = customerUsername;
    }

    public BigDecimal getDeliveryCost() {
        return deliveryCost;
    }

    public void setDeliveryCost(final BigDecimal deliveryCost) {
        this.deliveryCost = deliveryCost;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getAllergyRestrictions() {
        return allergyRestrictions;
    }

    public void setAllergyRestrictions(final String allergyRestrictions) {
        this.allergyRestrictions = allergyRestrictions;
    }
}
