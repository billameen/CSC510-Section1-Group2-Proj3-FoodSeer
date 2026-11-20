// Order statuses as the backend sends them (OrderStatus.label()).
// PLACED -> CLAIMED -> PICKED_UP -> DELIVERED, and anything not delivered
// can move to CANCELLED.
export const ORDER_STATUS = Object.freeze({
  PLACED: 'Placed',
  CLAIMED: 'Claimed',
  PICKED_UP: 'Picked Up',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
});

const ACTIVE = [ORDER_STATUS.PLACED, ORDER_STATUS.CLAIMED, ORDER_STATUS.PICKED_UP];

const BADGES = {
  [ORDER_STATUS.PLACED]: { text: '⏳ Placed', className: 'pending' },
  [ORDER_STATUS.CLAIMED]: { text: '🚗 Claimed by driver', className: 'pending' },
  [ORDER_STATUS.PICKED_UP]: { text: '🚚 On the way', className: 'pending' },
  [ORDER_STATUS.DELIVERED]: { text: '✓ Delivered', className: 'fulfilled' },
  [ORDER_STATUS.CANCELLED]: { text: '✕ Cancelled', className: 'cancelled' },
};

export const isActive = (order) => ACTIVE.includes(order?.status);
export const isDelivered = (order) => order?.status === ORDER_STATUS.DELIVERED;
export const isCancelled = (order) => order?.status === ORDER_STATUS.CANCELLED;

// Customers can only cancel before a driver claims the order.
export const customerCanCancel = (order) => order?.status === ORDER_STATUS.PLACED;
// Staff and admins can cancel anything that isn't delivered or already cancelled.
export const staffCanCancel = (order) => isActive(order);

export const statusBadge = (order) =>
  BADGES[order?.status] || { text: order?.status || 'Unknown', className: 'pending' };
