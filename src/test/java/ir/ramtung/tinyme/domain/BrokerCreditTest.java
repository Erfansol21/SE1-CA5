package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@DirtiesContext
public class BrokerCreditTest {
    @Autowired
    private Matcher matcher;
    private List<Order> orders;
    private Security security;
    private Broker firstBroker, secondBroker;
    private Shareholder shareholder;
    private OrderBook orderBook;



    @BeforeEach
    void setup(){

        security = Security.builder().isin("A").build();
        firstBroker = Broker.builder().brokerId(1).credit(10_000_000L).build();
        secondBroker = Broker.builder().brokerId(2).credit(10_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();

        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 15700, firstBroker, shareholder, 0),
                new Order(2, security, Side.BUY, 43, 15500, firstBroker, shareholder, 0),
                new Order(3, security, Side.BUY, 445, 15450, firstBroker, shareholder, 0),
                new Order(4, security, Side.BUY, 526, 15450, firstBroker, shareholder, 0),
                new Order(5, security, Side.BUY, 1000, 15400, firstBroker, shareholder, 0),
                new Order(7, security, Side.SELL, 285, 15810, secondBroker, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 15800, secondBroker, shareholder, 0),
                new Order(8, security, Side.SELL, 800, 15810, secondBroker, shareholder, 0),
                new Order(9, security, Side.SELL, 340, 15820, secondBroker, shareholder, 0),
                new Order(10, security, Side.SELL, 65, 15820, secondBroker, shareholder, 0)
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }
    //newOrder
    @Test
    void new_buy_order_is_completely_matched_and_the_credits_are_adjusted_correctly(){

        Order order = new Order(11, security, Side.BUY, 300, 15850, firstBroker, shareholder, 0);
        matcher.execute(order);
        assertThat(firstBroker.getCredit()).isEqualTo(10000000 - 300 * 15800);
        assertThat(secondBroker.getCredit()).isEqualTo(10000000 + 300 * 15800); // We can separate this test into two methods with similar code.
    }

//    @Test
//    void new_buy_order_is_completely_matched_and_the_sellers_credit_is_adjusted_correctly(){
//
//        Order order = new Order(11, security, Side.BUY, 300, 15850, firstBroker, shareholder);
//        MatchResult matchResult = matcher.execute(order);
//        assertThat(secondBroker.getCredit()).isEqualTo(10000000 + 300 * 15800);
//    }

    @Test
    void credit_adjustment_when_buy_order_is_partially_matched_and_placed_in_the_queue(){
        Order order = new Order(11, security, Side.BUY, 400, 15805, firstBroker, shareholder, 0);
        matcher.execute(order);
        assertThat(firstBroker.getCredit()).isEqualTo(10000000 - (350 * 15800 + 50 * 15805));
    }
    @Test
    void detecting_the_lack_of_credit_of_the_buyer(){
        Order order = new Order(11, security, Side.BUY, 1000, 15850, firstBroker, shareholder, 0);
        MatchResult matchResult = matcher.execute(order);
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_CREDIT);
        assertThat(firstBroker.getCredit()).isEqualTo(10000000);
    }
    @Test
    void Returning_the_credit_when_the_buyer_does_not_have_sufficient_credit(){
        Order order = new Order(11, security, Side.BUY, 1000, 15850, firstBroker, shareholder, 0);
        matcher.execute(order);
        assertThat(secondBroker.getCredit()).isEqualTo(10000000);
    }
    @Test
    void new_sell_order_is_completely_matched_and_the_credit_is_adjusted_correctly(){

        Order order = new Order(11, security, Side.SELL, 200, 15600, secondBroker, shareholder, 0);
        matcher.execute(order);
        assertThat(secondBroker.getCredit()).isEqualTo(10000000 + 200 * 15700);
    }
    // updateOrder
    @Test
    void credit_adjustment_when_update_buy_order_successfully_without_loss_of_Priority(){
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1, LocalDateTime.now(), Side.BUY, 204, 15700, 1, shareholder.getShareholderId(), 0, 0, 0);
        try {
            security.updateOrder(updateOrderRq, matcher);
            assertThat(firstBroker.getCredit()).isEqualTo(10000000 + 100 * 15700);

        }
        catch (InvalidRequestException ex){
            System.out.println("InvalidRequestException");
        }
    }
    @Test
    void credit_adjustment_when_update_buy_order_successfully_with_loss_of_Priority(){
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1, LocalDateTime.now(), Side.BUY, 404, 15800, 1, shareholder.getShareholderId(), 0, 0, 0);
        try {
            security.updateOrder(updateOrderRq, matcher);
            assertThat(firstBroker.getCredit()).isEqualTo(10000000 - 404 * 15800 + 304 * 15700);

        }
        catch (InvalidRequestException ex){
            System.out.println("InvalidRequestException");
        }
    }
    @Test
    void detecting_request_for_update_without_sufficient_credit(){ // We can write this test in a situation where price has changed.
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1, LocalDateTime.now(), Side.BUY, 1004, 15700, 1, shareholder.getShareholderId(), 0, 0, 0);
        try {
            SecurityStatus securityStatus = security.updateOrder(updateOrderRq, matcher);
            assertThat(securityStatus.requestStatus()).isEqualTo(RequestStatus.NOT_ENOUGH_CREDIT);
            assertThat(firstBroker.getCredit()).isEqualTo(10000000);
        }
        catch (InvalidRequestException ex){
            System.out.println("Unable to find the desired order.");
        }
    }

    //deleteOrder
    @Test
    void returning_the_credit_when_an_buy_order_is_deleted(){
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.BUY, 1);
        try {
            security.deleteOrder(deleteOrderRq);
            assertThat(firstBroker.getCredit()).isEqualTo(10000000 + 304 * 15700);
        }
        catch (InvalidRequestException ex){
            System.out.println("Unable to find the desired order.");
            // I could use OrderHandler for this test, but I think it's better to use Security because it is a UNIT test.
            // Also, I could use eventPublisher here(like OrderHandler), but I want to involve fewer elements.
        }
    }
}