package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();
        int openingPrice = newOrder.getSecurity().getOpeningPrice();
        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;
            int price = newOrder.getSecurity().getState() == MatchingState.CONTINUOUS ? matchingOrder.getPrice() : openingPrice;

            Trade trade = new Trade(newOrder.getSecurity(), price, Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSecurity().getState() == MatchingState.CONTINUOUS) {
                if (newOrder.getSide() == Side.BUY) {
                    if (trade.buyerHasEnoughCredit())
                        trade.decreaseBuyersCredit();
                    else {
                        rollbackTrades(newOrder, trades);
                        return MatchResult.notEnoughCredit();
                    }
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }
        return MatchResult.executed(newOrder, trades);
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getSide() == Side.BUY) {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));
        }
        else
            newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());

        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            if (newOrder.getSide() == Side.BUY)
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getSell());
            else
                newOrder.getSecurity().getOrderBook().restoreOrder(it.previous().getBuy());
        }
    }

    public MatchResult execute(Order order) {
        int initialQuantity = order.getQuantity();
        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT)
            return result;


        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }

                order.getBroker().decreaseCreditBy(order.getValue());
            }
            if (order.isNew() && order.getMinimumExecutionQuantity() > (initialQuantity - result.remainder().getQuantity())){
                rollbackTrades(order, result.trades());
                return MatchResult.notEnoughInitialTransaction();
            }

            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
        return result;
    }
    public MatchResult executeAuction(Order order){
        int initialQuantity = order.getQuantity();
        MatchResult result = match(order);

        if (result.remainder().getQuantity() > 0)
            order.getSecurity().getOrderBook().enqueue(result.remainder());

        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy((long) (order.getPrice() - order.getSecurity().getOpeningPrice()) * (initialQuantity - order.getQuantity()));

        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
        return result;
    }

}
