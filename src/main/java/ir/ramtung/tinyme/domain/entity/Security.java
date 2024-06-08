package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import lombok.Builder;
import lombok.Getter;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import static java.lang.Math.abs;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private InactiveOrderBook inactiveOrderBook = new InactiveOrderBook();
    @Builder.Default
    private int lastTransactionPrice = 0;
    @Builder.Default
    private final LinkedList<Order> executableOrders = new LinkedList<>();
    @Builder.Default
    private MatchingState state = MatchingState.CONTINUOUS;
    @Builder.Default
    private int openingPrice = 0;


    public SecurityStatus newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) throws InvalidRequestException {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return SecurityStatus.notEnoughPositions();
        Order order;
        if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() == 0)
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getMinimumExecutionQuantity());
        else if (enterOrderRq.getPeakSize() != 0 && enterOrderRq.getStopPrice() == 0)
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), enterOrderRq.getMinimumExecutionQuantity());
        else if (enterOrderRq.getStopPrice() != 0 && enterOrderRq.getPeakSize() == 0) {
            StopLimitOrder stopLimitOrder = new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getMinimumExecutionQuantity(),
                    enterOrderRq.getStopPrice(), enterOrderRq.getRequestId());
            if ( (stopLimitOrder.getSide() == Side.BUY && stopLimitOrder.getStopPrice() <= lastTransactionPrice) || (stopLimitOrder.getSide() == Side.SELL && stopLimitOrder.getStopPrice() >= lastTransactionPrice) ){
                order = stopLimitOrder;
            }
            else{
                if (stopLimitOrder.getSide() == Side.BUY) {
                    if (!stopLimitOrder.getBroker().hasEnoughCredit(stopLimitOrder.getValue())) {
                        return SecurityStatus.notEnoughCredit();
                    }
                }
                inactiveOrderBook.enqueue(stopLimitOrder);
                return SecurityStatus.queuedAsInactiveOrder();
            }
        }
        else
            throw new InvalidRequestException("Panic");
        if (state == MatchingState.AUCTION){
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    return SecurityStatus.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            orderBook.enqueue(order);
            return SecurityStatus.auctioned();
        }
        MatchResult matchResult = matcher.execute(order);
        return createAppropriateStatus(matchResult, enterOrderRq);
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (order == null) {
            order = inactiveOrderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        }
        if (order instanceof StopLimitOrder stopLimitOrder) {
            inactiveOrderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
            return;
        }
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
    }

    public SecurityStatus updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order;
        if (updateOrderRq.getStopPrice() != 0) {
            order = inactiveOrderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        }
        else
            order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());

        order.markAsUpdated();

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return SecurityStatus.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);

        if (order instanceof StopLimitOrder stopLimitOrder) {
            if (stopLimitOrder.mustBeActive(lastTransactionPrice)){
                inactiveOrderBook.removeByOrderId(stopLimitOrder.getSide(), stopLimitOrder.getOrderId());
                MatchResult matchResult = matcher.execute((Order) stopLimitOrder);
                return createAppropriateStatus(matchResult, updateOrderRq);
            }
            else {
                if (stopLimitOrder.getStopPrice() != ((StopLimitOrder) originalOrder).getStopPrice()){
                    inactiveOrderBook.removeByOrderId(stopLimitOrder.getSide(), stopLimitOrder.getOrderId());
                    inactiveOrderBook.enqueue(stopLimitOrder);
                    return SecurityStatus.updated();
                }
            }
        }

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(originalOrder.getValue());
        }

        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return SecurityStatus.updated();
        }
        else
            order.markAsNew();

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (state == MatchingState.CONTINUOUS) {
            MatchResult matchResult = matcher.execute(order);
            if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
                orderBook.enqueue(originalOrder);
                if (updateOrderRq.getSide() == Side.BUY) {
                    originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
                }
            } else if (!matchResult.trades().isEmpty())
                lastTransactionPrice = matchResult.trades().getLast().getPrice();

            return createAppropriateStatus(matchResult, updateOrderRq);
        }
        else{
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue())) {
                    return SecurityStatus.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            orderBook.enqueue(order);
            return SecurityStatus.updated();
        }
    }

    public void checkExecutableOrders(int tradePrice) {
        int previousTransactionPrice = lastTransactionPrice;
        lastTransactionPrice = tradePrice;
        if (lastTransactionPrice == previousTransactionPrice) {
            return;
        }
        Side targetSide = (lastTransactionPrice - previousTransactionPrice) > 0 ? Side.BUY : Side.SELL;
        findExecutableOrders(targetSide);
    }

    public void findExecutableOrders(Side side){
        while (inactiveOrderBook.hasOrderOfType(side)){
            StopLimitOrder stopLimitOrder = inactiveOrderBook.checkFirstInactiveOrder(side, lastTransactionPrice);
            if(stopLimitOrder == null){
                return;
            }
            executableOrders.add(stopLimitOrder);
            inactiveOrderBook.removeFirst(side);
        }
    }

    public LinkedList<MatchResult> enqueueExecutableOrders(){
        LinkedList<MatchResult> results = new LinkedList<>();
        while (!executableOrders.isEmpty()){
            StopLimitOrder executableOrder = (StopLimitOrder) executableOrders.removeFirst();
            orderBook.enqueue(executableOrder);
            results.add(MatchResult.activated(executableOrder));
        }
        return results;
    }

    public LinkedList<MatchResult> handleExecutableOrders(int tradePrice, Matcher matcher){
        checkExecutableOrders(tradePrice);
        LinkedList<MatchResult> results = new LinkedList<>();
        while (!executableOrders.isEmpty()){
            StopLimitOrder executableOrder = (StopLimitOrder) executableOrders.removeFirst();
            MatchResult matchResult = matcher.execute(executableOrder);
            if (!matchResult.trades().isEmpty()) {
                checkExecutableOrders(matchResult.trades().getLast().getPrice());
            }
            results.add(matchResult);
        }
        return results;
    }

    public OpeningData findOpeningData(){
         OpeningData openingData = orderBook.findPriceBasedOnMaxTransaction().findClosestPriceToLastTransaction(lastTransactionPrice);
         openingPrice = openingData.getOpeningPrice();
         return openingData;
    }

    private OpeningData findMinimumPrice(List<OpeningRangeData> possiblePrices){
        int openingPrice = possiblePrices.stream().mapToInt(OpeningRangeData::getMinOpeningPrice).min().orElse(-1);
        int tradableQuantity = possiblePrices.get(0).getTradableQuantity();
        return new OpeningData(openingPrice, tradableQuantity);
        // Bad implement
    }

    public void changeMatchingState(MatchingState targetState){
        state = targetState;
    }


    public LinkedList<MatchResult> runAuctionedOrders(Matcher matcher){
        LinkedList<MatchResult> results = new LinkedList<>();
        LinkedList<Order> buyOrders = orderBook.getQueue(Side.BUY);
        while (orderBook.hasOrderOfType(Side.BUY) && orderBook.hasOrderOfType(Side.SELL)){
            Order auctionedOrder = buyOrders.removeFirst();

            MatchResult matchResult = matcher.executeAuction(auctionedOrder);
            if (matchResult.trades().isEmpty()){
                buyOrders.addFirst(auctionedOrder);
                break;
            }
            results.add(matchResult);
        }
        return results;
    }

    private SecurityStatus createAppropriateStatus(MatchResult matchResult, EnterOrderRq enterOrderRq){
        switch (matchResult.outcome()){
            case EXECUTED -> {
                if (enterOrderRq.getStopPrice() == 0) {
                    if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                        return SecurityStatus.accepted(matchResult.trades());
                    else
                        return SecurityStatus.updated(matchResult.trades());
                }
                else {
                    if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                        return SecurityStatus.acceptedAndActivated(matchResult.trades());
                    else
                        return SecurityStatus.updatedAndActivated(matchResult.trades());
                }
            }
            case NOT_ENOUGH_CREDIT -> {return SecurityStatus.notEnoughCredit();}
            case NOT_ENOUGH_POSITIONS -> {return SecurityStatus.notEnoughPositions();}
            case NOT_ENOUGH_INITIAL_TRANSACTION -> {return SecurityStatus.notEnoughInitialTransaction();}
            default -> {return SecurityStatus.notEnoughCredit();}
        }
    }
}


