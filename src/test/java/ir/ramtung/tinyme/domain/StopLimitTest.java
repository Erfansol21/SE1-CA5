package ir.ramtung.tinyme.domain;


import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static ir.ramtung.tinyme.domain.entity.Side.SELL;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopLimitTest {

    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    @Autowired
    Matcher matcher;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;

    @BeforeEach
    void setup() {
        security = Security.builder().isin("ABC").lastTransactionPrice(5).build();
        broker = Broker.builder().brokerId(1).credit(1_000_000L).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 100_000_000);

        brokerRepository.addBroker(broker);
        shareholderRepository.addShareholder(shareholder);
        securityRepository.addSecurity(security);
    }

    @Test
    void new_buy_order_gets_activated_when_stop_limit_is_less_than_last_transaction_price() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 3);

        orderHandler.handleEnterOrder(stopLimitOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void new_sell_order_gets_activated_when_stop_limit_is_more_than_last_transaction_price() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);

        orderHandler.handleEnterOrder(stopLimitOrderRq);

        assertThat(security.getInactiveOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void last_transaction_price_change_after_an_order_get_executed() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);

        orderHandler.handleEnterOrder(stopLimitOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 10, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 2, LocalDateTime.now(), BUY, 3, 10, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getLastTransactionPrice()).isEqualTo(10);
    }

    @Test
    void new_buy_order_gets_activated_when_last_transaction_price_becomes_more_than_stop_limit() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);

        orderHandler.handleEnterOrder(stopLimitOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 10, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 10, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void new_sell_order_gets_activated_when_last_transaction_price_became_less_than_stop_limit() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 4);

        orderHandler.handleEnterOrder(stopLimitOrderRq);
        assertThat(security.getInactiveOrderBook().getSellQueue()).isNotEmpty();

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 3, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 3, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void two_new_buy_order_one_gets_activated_and_other_one_wont_when_last_transaction_price_is_between_their_stop_limit() {
        EnterOrderRq stopLimitOrderRq1 = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);
        EnterOrderRq stopLimitOrderRq2 = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 9);

        orderHandler.handleEnterOrder(stopLimitOrderRq1);
        orderHandler.handleEnterOrder(stopLimitOrderRq2);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 8, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 8, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue().get(0).getOrderId()).isEqualTo(2);
    }

    @Test
    void two_new_sell_order_one_gets_activated_and_other_one_wont_when_last_transaction_price_is_between_their_stop_limit() {
        EnterOrderRq stopLimitOrderRq1 = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 4);
        EnterOrderRq stopLimitOrderRq2 = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 2);

        orderHandler.handleEnterOrder(stopLimitOrderRq1);
        orderHandler.handleEnterOrder(stopLimitOrderRq2);
        assertThat(security.getInactiveOrderBook().getSellQueue()).isNotEmpty();

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 3, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 3, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getSellQueue().get(0).getOrderId()).isEqualTo(2);
    }

    @Test
    void two_new_buy_order_one_gets_executed_and_change_last_transaction_price_but_it_wont_effect_other_one() {
        EnterOrderRq stopLimitOrderRq1 = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 2, 1, shareholder.getShareholderId(), 0, 0, 7);
        EnterOrderRq stopLimitOrderRq2 = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 9);

        orderHandler.handleEnterOrder(stopLimitOrderRq1);
        orderHandler.handleEnterOrder(stopLimitOrderRq2);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();

        Order sellOrder = new Order(34, security, SELL, 3, 2, broker, shareholder, 0);
        security.getOrderBook().enqueue(sellOrder);

        Order changingStopLimitSellOrder = new Order(3, security, BUY, 3, 10, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 3, LocalDateTime.now(), SELL, 3, 10, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void two_new_sell_order_one_gets_executed_and_change_last_transaction_price_but_it_wont_effect_other_one() {
        EnterOrderRq stopLimitOrderRq1 = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), SELL, 3, 15, 1, shareholder.getShareholderId(), 0, 0, 2);
        EnterOrderRq stopLimitOrderRq2 = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 4);

        orderHandler.handleEnterOrder(stopLimitOrderRq1);
        orderHandler.handleEnterOrder(stopLimitOrderRq2);
        assertThat(security.getInactiveOrderBook().getSellQueue()).isNotEmpty();

        Order buyOrder = new Order(34, security, BUY, 3, 15, broker, shareholder, 0);
        security.getOrderBook().enqueue(buyOrder);

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 1, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 1, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void two_new_buy_order_one_gets_executed_and_change_last_transaction_price_and_make_other_one_get_activated() {
        EnterOrderRq stopLimitOrderRq1 = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 10, 1, shareholder.getShareholderId(), 0, 0, 6);
        EnterOrderRq stopLimitOrderRq2 = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 9);

        orderHandler.handleEnterOrder(stopLimitOrderRq1);
        orderHandler.handleEnterOrder(stopLimitOrderRq2);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();

        Order sellOrder = new Order(34, security, SELL, 3, 10, broker, shareholder, 0);
        security.getOrderBook().enqueue(sellOrder);

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 7, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void two_new_sell_order_one_gets_executed_and_change_last_transaction_price_and_make_other_one_get_activated() {
        EnterOrderRq stopLimitOrderRq1 = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), SELL, 3, 1, 1, shareholder.getShareholderId(), 0, 0, 4);
        EnterOrderRq stopLimitOrderRq2 = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 2);

        orderHandler.handleEnterOrder(stopLimitOrderRq1);
        orderHandler.handleEnterOrder(stopLimitOrderRq2);
        assertThat(security.getInactiveOrderBook().getSellQueue()).isNotEmpty();


        Order buyOrder = new Order(34, security, BUY, 3, 1, broker, shareholder, 0);
        security.getOrderBook().enqueue(buyOrder);

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 3, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 3, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void new_buy_order_gets_executed_and_make_a_new_sell_order_activated() {
        EnterOrderRq stopLimitOrderRqBuy = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 2, 1, shareholder.getShareholderId(), 0, 0, 6);
        EnterOrderRq stopLimitOrderRqSell = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), SELL, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 3);

        orderHandler.handleEnterOrder(stopLimitOrderRqBuy);
        orderHandler.handleEnterOrder(stopLimitOrderRqSell);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();
        assertThat(security.getInactiveOrderBook().getSellQueue()).isNotEmpty();

        Order sellOrder = new Order(34, security, SELL, 3, 2, broker, shareholder, 0);
        security.getOrderBook().enqueue(sellOrder);

        Order changingStopLimitSellOrder = new Order(3, security, BUY, 3, 7, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), SELL, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getInactiveOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void new_sell_order_gets_executed_and_make_a_new_buy_order_activated() {
        EnterOrderRq stopLimitOrderRqBuy = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 2, 1, shareholder.getShareholderId(), 0, 0, 6);
        EnterOrderRq stopLimitOrderRqSell = EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), SELL, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 3);

        orderHandler.handleEnterOrder(stopLimitOrderRqBuy);
        orderHandler.handleEnterOrder(stopLimitOrderRqSell);
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isNotEmpty();
        assertThat(security.getInactiveOrderBook().getSellQueue()).isNotEmpty();

        Order buyOrder = new Order(34, security, BUY, 3, 7, broker, shareholder, 0);
        security.getOrderBook().enqueue(buyOrder);

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 2, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 2, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
        assertThat(security.getInactiveOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void new_order_with_both_peak_size_and_stop_limit_fails() {
        EnterOrderRq newOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 2, 1, shareholder.getShareholderId(), 1, 0, 5);
        orderHandler.handleEnterOrder(newOrderRq);
        LinkedList<String> errors = new LinkedList<>();
        errors.add(Message.ORDER_CANNOT_BE_BOTH_A_STOP_LIMIT_AND_AN_ICEBERG);
        verify(eventPublisher).publish(new OrderRejectedEvent(newOrderRq.getRequestId(), newOrderRq.getOrderId(), errors));
    }

    @Test
    void event_publisher_publish_activated_order_when_order_get_activated_when_its_published() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 3);

        orderHandler.handleEnterOrder(stopLimitOrderRq);

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 1));
    }

    @Test
    void event_publisher_publish_activated_order_when_order_get_activated_when_its_stop_limit_is_met() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 55, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);

        orderHandler.handleEnterOrder(stopLimitOrderRq);

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 10, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 1, LocalDateTime.now(), BUY, 3, 10, 1, shareholder.getShareholderId(), 0, 0, 0);

        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 55));
    }

    @Test
    void event_publisher_publish_executed_order_when_order_get_executed() {
        StopLimitOrder buyStopLimitOrder = new StopLimitOrder(55, security, BUY,3 , 10, broker, shareholder, 0, 6, 1);

        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 55, LocalDateTime.now(), BUY, 3, 10, 1, shareholder.getShareholderId(), 0, 0, 6);
        orderHandler.handleEnterOrder(stopLimitOrderRq);

        Order sellOrder = new Order(34, security, SELL, 3, 10, broker, shareholder, 0);
        security.getOrderBook().enqueue(sellOrder);

        Order changingStopLimitSellOrder = new Order(3, security, Side.SELL, 3, 7, broker, shareholder, 0);
        security.getOrderBook().enqueue(changingStopLimitSellOrder);
        EnterOrderRq ChangingStopLimitnewOrderRq = EnterOrderRq.createNewOrderRq(3, "ABC", 4, LocalDateTime.now(), BUY, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 0);
        orderHandler.handleEnterOrder(ChangingStopLimitnewOrderRq);

        Trade trade = new Trade(security, sellOrder.getPrice(), sellOrder.getQuantity(),
                buyStopLimitOrder, sellOrder);

        verify(eventPublisher).publish(new OrderExecutedEvent(1, 55, List.of(new TradeDTO(trade))));
    }

    @Test
    void update_order_if_order_is_not_activated_and_order_would_not_get_activated_after_update() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);
        assertThatNoException().isThrownBy(() -> security.newOrder(stopLimitOrderRq, broker, shareholder, matcher));

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(3, security.getIsin(), 1, LocalDateTime.now(), BUY, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 6);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(security.getInactiveOrderBook().getBuyQueue().get(0).getPrice()).isEqualTo(7);
    }

    @Test
    void update_order_if_order_is_not_activated_and_order_get_activated_after_update() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 13);
        assertThatNoException().isThrownBy(() -> security.newOrder(stopLimitOrderRq, broker, shareholder, matcher));

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(3, security.getIsin(), 1, LocalDateTime.now(), BUY, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 3);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
    }

    @Test
    void event_publisher_publish_activated_order_when_order_get_activated_after_update() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 13);
        assertThatNoException().isThrownBy(() -> security.newOrder(stopLimitOrderRq, broker, shareholder, matcher));

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(3, security.getIsin(), 1, LocalDateTime.now(), BUY, 3, 7, 1, shareholder.getShareholderId(), 0, 0, 3);
        orderHandler.handleEnterOrder(updateOrderRq);

        verify(eventPublisher).publish(new OrderActivatedEvent(3, 1));
    }

    @Test
    void delete_order_stop_limit_order() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, 1, shareholder.getShareholderId(), 0, 0, 7);
        assertThatNoException().isThrownBy(() -> security.newOrder(stopLimitOrderRq, broker, shareholder, matcher));

        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(3, security.getIsin(), BUY, 1);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        assertThat(security.getInactiveOrderBook().getBuyQueue()).isEmpty();
    }
    @Test
    void new_stop_limit_order_without_enough_credit_fails() {
        EnterOrderRq newOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), BUY, 3, 2000000, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 5);
        orderHandler.handleEnterOrder(newOrderRq);
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 1, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void new_stop_limit_order_without_enough_position_fails() {
        shareholder.decPosition(security, 99_999_999);
        broker.increaseCreditBy(100_000_000);

        EnterOrderRq newOrderRq = EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), SELL, 3000000, 2, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 5);
        orderHandler.handleEnterOrder(newOrderRq);
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 1, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_order_invalid_if_order_is_activated_and_we_give_stop_limit() {
        EnterOrderRq stopLimitOrderRq = EnterOrderRq.createNewOrderRq(2, "ABC", 1, LocalDateTime.now(), BUY, 3, 5, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 3);
        assertThatNoException().isThrownBy(() -> security.newOrder(stopLimitOrderRq, broker, shareholder, matcher));

        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(3, security.getIsin(), 1, LocalDateTime.now(), BUY, 2, 7, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0, 2);
        orderHandler.handleEnterOrder(updateOrderRq);
        verify(eventPublisher).publish(new OrderRejectedEvent(3, 1, List.of(Message.ORDER_ID_NOT_FOUND)));
    }
}
