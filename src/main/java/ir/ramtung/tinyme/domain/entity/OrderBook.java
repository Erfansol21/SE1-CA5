package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.*;

import static java.lang.Math.max;
import static java.lang.Math.min;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.queue();
        it.add(order);
    }

    protected LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.queue();
        queue.addFirst(order);
    }

    public void restoreOrder(Order order) {
        removeByOrderId(order.getSide(), order.getOrderId());
        putBack(order);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public void removeFirst(Side side) {
        getQueue(side).removeFirst();
    }

    public Order getFirst(Side side) { return getQueue(side).getFirst(); }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public OpeningRangeData findPriceBasedOnMaxTransaction() {
        int minOpeningPrice = Integer.MAX_VALUE, maxOpeningPrice = Integer.MIN_VALUE;
        int maxTradeQuantity = 0;
        int sellQuantity = sellQueue.stream().mapToInt(Order::getTotalQuantity).sum(), buyQuantity = 0;
        ListIterator<Order> buyQueueIt = buyQueue.listIterator();
        ListIterator<Order> sellQueueIt = sellQueue.listIterator(sellQueue.size());
        while (sellQueueIt.hasPrevious()) {
            Order sellOrder = sellQueueIt.previous();
            int maxPossiblePrice = -1;
            while (buyQueueIt.hasNext()) {
                Order buyOrder = buyQueueIt.next();
                if (sellOrder.getPrice() <= buyOrder.getPrice()) {
                    buyQuantity += buyOrder.getQuantity();
                    maxPossiblePrice = buyOrder.getPrice();
                }
                else {
                    buyQueueIt.previous();
                    break;
                }
            }
            if (min(sellQuantity, buyQuantity) > maxTradeQuantity) {
                minOpeningPrice = sellOrder.getPrice();
                maxOpeningPrice = maxPossiblePrice;
                maxTradeQuantity = min(sellQuantity, buyQuantity);
            }
            else if (min(sellQuantity, buyQuantity) == maxTradeQuantity)
                minOpeningPrice = sellOrder.getPrice();
            sellQuantity -= sellOrder.getQuantity();
        }
        return new OpeningRangeData(minOpeningPrice, maxOpeningPrice, maxTradeQuantity);
    }
    public OrderBook getExecutableOrdersWithPrise(int openingPrice){
        OrderBook orderBook = new OrderBook();
        ListIterator<Order> buyQueueIt = buyQueue.listIterator();
        ListIterator<Order> sellQueueIt = sellQueue.listIterator();
        while (buyQueueIt.hasNext()) {
            Order buyOrder = buyQueueIt.next();
            if (buyOrder.getPrice() < openingPrice)
                break;
            else {
                orderBook.buyQueue.add(buyOrder);
                buyQueue.remove();
            }
        }
        while (sellQueueIt.hasNext()) {
            Order sellOrder = sellQueueIt.next();
            if (sellOrder.getPrice() > openingPrice)
                break;
            else{
                orderBook.sellQueue.add(sellOrder);
                sellQueue.remove();
            }
        }
        return orderBook;
    }


}
