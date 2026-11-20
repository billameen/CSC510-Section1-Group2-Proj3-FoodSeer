import {
  ORDER_STATUS,
  isActive,
  isDelivered,
  isCancelled,
  customerCanCancel,
  staffCanCancel,
  statusBadge,
} from './orderStatus';

const order = (status) => ({ id: 1, status });

describe('orderStatus', () => {
  test('active means placed, claimed or picked up', () => {
    expect(isActive(order(ORDER_STATUS.PLACED))).toBe(true);
    expect(isActive(order(ORDER_STATUS.CLAIMED))).toBe(true);
    expect(isActive(order(ORDER_STATUS.PICKED_UP))).toBe(true);
    expect(isActive(order(ORDER_STATUS.DELIVERED))).toBe(false);
    expect(isActive(order(ORDER_STATUS.CANCELLED))).toBe(false);
    expect(isActive(null)).toBe(false);
  });

  test('delivered and cancelled are told apart', () => {
    expect(isDelivered(order(ORDER_STATUS.DELIVERED))).toBe(true);
    expect(isCancelled(order(ORDER_STATUS.DELIVERED))).toBe(false);
    expect(isCancelled(order(ORDER_STATUS.CANCELLED))).toBe(true);
  });

  test('customers can cancel only while placed', () => {
    expect(customerCanCancel(order(ORDER_STATUS.PLACED))).toBe(true);
    [ORDER_STATUS.CLAIMED, ORDER_STATUS.PICKED_UP, ORDER_STATUS.DELIVERED, ORDER_STATUS.CANCELLED]
      .forEach((s) => expect(customerCanCancel(order(s))).toBe(false));
  });

  test('staff can cancel anything not finished', () => {
    expect(staffCanCancel(order(ORDER_STATUS.PICKED_UP))).toBe(true);
    expect(staffCanCancel(order(ORDER_STATUS.DELIVERED))).toBe(false);
    expect(staffCanCancel(order(ORDER_STATUS.CANCELLED))).toBe(false);
  });

  test('badge falls back to the raw status', () => {
    expect(statusBadge(order(ORDER_STATUS.CANCELLED)).className).toBe('cancelled');
    expect(statusBadge(order('Weird')).text).toBe('Weird');
  });
});
