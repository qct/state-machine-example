package com.example.orderservice;

import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.config.EnableStateMachineFactory;
import org.springframework.statemachine.config.StateMachineConfigurerAdapter;
import org.springframework.statemachine.config.StateMachineFactory;
import org.springframework.statemachine.config.builders.StateMachineConfigurationConfigurer;
import org.springframework.statemachine.config.builders.StateMachineStateConfigurer;
import org.springframework.statemachine.config.builders.StateMachineTransitionConfigurer;
import org.springframework.statemachine.listener.StateMachineListenerAdapter;
import org.springframework.statemachine.state.State;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import org.springframework.statemachine.support.StateMachineInterceptorAdapter;
import org.springframework.statemachine.transition.Transition;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

enum OrderEvents {
    FULFILL,
    PAY,
    CANCEL
}

enum OrderStates {
    SUBMITTED,
    PAID,
    FULFILLED,
    CANCELLED
}

@Service
class OrderService {

    private final OrderRepository orderRepository;
    private final StateMachineFactory<OrderStates, OrderEvents> factory;

    private static final String ORDER_ID_HEADER = "orderId";

    OrderService(OrderRepository orderRepository, StateMachineFactory<OrderStates, OrderEvents> factory) {
        this.orderRepository = orderRepository;
        this.factory = factory;
    }

    Order create(Date when) {
        return this.orderRepository.save(new Order(when, OrderStates.SUBMITTED));
    }

    Order byId(Long id) {
        return this.orderRepository.findById(id).orElse(null);
    }

    StateMachine<OrderStates, OrderEvents> fulfill(Long orderId) {
        StateMachine<OrderStates, OrderEvents> sm = this.build(orderId);
        Message<OrderEvents> fulfillMessage = MessageBuilder.withPayload(OrderEvents.FULFILL)
            .setHeader(ORDER_ID_HEADER, orderId)
            .build();
        sm.sendEvent(fulfillMessage);
        return sm;
    }

    StateMachine<OrderStates, OrderEvents> pay(Long orderId, String paymentConfirmationNumber) {
        StateMachine<OrderStates, OrderEvents> sm = this.build(orderId);

        Message<OrderEvents> paymentMessage = MessageBuilder.withPayload(OrderEvents.PAY)
            .setHeader(ORDER_ID_HEADER, orderId)
            .setHeader("paymentConfirmationNumber", paymentConfirmationNumber)
            .build();

        sm.sendEvent(paymentMessage);
        return sm;
    }

    private StateMachine<OrderStates, OrderEvents> build(Long orderId) {
        Order order = this.orderRepository.findById(orderId).orElse(null);
        String orderIdKey = Long.toString(order.getId());
        StateMachine<OrderStates, OrderEvents> sm = this.factory.getStateMachine(orderIdKey);
        sm.stop();
        sm.getStateMachineAccessor()
            .doWithAllRegions(sma -> {

                sma.addStateMachineInterceptor(new StateMachineInterceptorAdapter<OrderStates, OrderEvents>() {

                    @Override
                    public void preStateChange(State<OrderStates, OrderEvents> state, Message<OrderEvents> message,
                        Transition<OrderStates, OrderEvents> transition,
                        StateMachine<OrderStates, OrderEvents> stateMachine) {

                        Optional.ofNullable(message).ifPresent(msg ->
                            Optional.ofNullable(Long.class.cast(msg.getHeaders().getOrDefault(ORDER_ID_HEADER, -1L)))
                                .ifPresent(orderId1 -> {
                                    Order order1 = orderRepository.findById(orderId1).orElse(null);
                                    order1.setOrderState(state.getId());
                                    orderRepository.save(order1);
                                }));
                    }
                });
                sma.resetStateMachine(new DefaultStateMachineContext<>(order.getOrderState(), null, null, null));
            });
        sm.start();
        return sm;
    }
}

@Log
@Component
class Runner implements ApplicationRunner {

    private final OrderService orderService;

    Runner(OrderService orderService) {
        this.orderService = orderService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Order order = this.orderService.create(new Date());
        StateMachine<OrderStates, OrderEvents> paymentStateMachine = orderService.pay(order.getId(), UUID.randomUUID()
            .toString());
        log.info("after calling pay(): " + paymentStateMachine.getState().getId().name());
        log.info("order: " + orderService.byId(order.getId()));

        StateMachine<OrderStates, OrderEvents> fulfilledStateMachine = orderService.fulfill(order.getId());
        log.info("after calling fulfill(): " + fulfilledStateMachine.getState().getId().name());
        log.info("order: " + orderService.byId(order.getId()));


    }

}

interface OrderRepository extends JpaRepository<Order, Long> {

}

@Entity(name = "ORDERS")
@Data
@NoArgsConstructor
@AllArgsConstructor
class Order {

    @Id
    @GeneratedValue
    private Long id;
    private Date datetime;

    private String state;

    public Order(Date date, OrderStates states) {
        this.datetime = date;
        this.state = states.name();
    }

    public OrderStates getOrderState() {
        return OrderStates.valueOf(this.state);
    }

    public void setOrderState(OrderStates state) {
        this.state = state.name();
    }

}

@Log
@Configuration
@EnableStateMachineFactory
class SimpleEnumStatemachineConfiguration extends StateMachineConfigurerAdapter<OrderStates, OrderEvents> {

    @Override
    public void configure(StateMachineTransitionConfigurer<OrderStates, OrderEvents> transitions) throws Exception {
        transitions
            .withExternal().source(OrderStates.SUBMITTED).target(OrderStates.PAID).event(OrderEvents.PAY)
            .and()
            .withExternal().source(OrderStates.PAID).target(OrderStates.FULFILLED).event(OrderEvents.FULFILL)
            .and()
            .withExternal().source(OrderStates.SUBMITTED).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL)
            .and()
            .withExternal().source(OrderStates.PAID).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL)
            .and()
            .withExternal().source(OrderStates.FULFILLED).target(OrderStates.CANCELLED).event(OrderEvents.CANCEL);

    }

    @Override
    public void configure(StateMachineStateConfigurer<OrderStates, OrderEvents> states) throws Exception {
        states
            .withStates()
            .initial(OrderStates.SUBMITTED)
            .stateEntry(OrderStates.SUBMITTED, stateContext -> {
                Long orderId = (Long) stateContext.getExtendedState().getVariables().getOrDefault("orderId", -1L);
                log.info("orderId is " + orderId + ".");
                log.info("entering submitted state!");
            })
            .state(OrderStates.PAID)
            .end(OrderStates.CANCELLED)
            .end(OrderStates.FULFILLED);
    }

    @Override
    public void configure(StateMachineConfigurationConfigurer<OrderStates, OrderEvents> config) throws Exception {
        StateMachineListenerAdapter<OrderStates, OrderEvents> adapter = new StateMachineListenerAdapter<OrderStates, OrderEvents>() {
            @Override
            public void stateChanged(State<OrderStates, OrderEvents> from, State<OrderStates, OrderEvents> to) {
                log.info(String.format("stateChanged(from: %s, to: %s)", from + "", to + ""));
            }
        };
        config.withConfiguration()
            .autoStartup(false)
            .listener(adapter);
    }
}
