package com.stockforge.order.adapter.in.grpc;

import static com.stockforge.grpc.GrpcProblems.problem;

import com.stockforge.grpc.GrpcProblems;
import com.stockforge.order.application.OrderQueries;
import com.stockforge.order.application.PlaceOrderCommand;
import com.stockforge.order.application.PlaceOrderResult;
import com.stockforge.order.application.PlaceOrderUseCase;
import com.stockforge.order.application.ReplayDeadLettersUseCase;
import com.stockforge.order.domain.model.Order;
import com.stockforge.order.domain.model.OrderId;
import com.stockforge.order.domain.model.ReservationId;
import com.stockforge.order.domain.model.UserId;
import com.stockforge.order.domain.port.OrderRepository.PageKey;
import com.stockforge.order.v1.CreateOrderRequest;
import com.stockforge.order.v1.CreateOrderResponse;
import com.stockforge.order.v1.GetOrderRequest;
import com.stockforge.order.v1.GetOrderResponse;
import com.stockforge.order.v1.ListOrdersRequest;
import com.stockforge.order.v1.ListOrdersResponse;
import com.stockforge.order.v1.OrderItem;
import com.stockforge.order.v1.OrderServiceGrpc;
import com.stockforge.order.v1.ReplayDeadLettersRequest;
import com.stockforge.order.v1.ReplayDeadLettersResponse;
import com.stockforge.order.v1.StatusChange;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;

/** Inbound gRPC adapter of the order service, used by the API gateway (spec §12). Translation only. */
@GrpcService
class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final PlaceOrderUseCase placeOrder;
    private final OrderQueries queries;
    private final ReplayDeadLettersUseCase replayDeadLetters;

    OrderGrpcService(PlaceOrderUseCase placeOrder, OrderQueries queries, ReplayDeadLettersUseCase replayDeadLetters) {
        this.placeOrder = placeOrder;
        this.queries = queries;
        this.replayDeadLetters = replayDeadLetters;
    }

    @Override
    public void createOrder(CreateOrderRequest request, StreamObserver<CreateOrderResponse> response) {
        PlaceOrderCommand command = new PlaceOrderCommand(
                request.getIdempotencyKey(),
                UserId.of(uuid(request.getUserId(), "user_id")),
                ReservationId.of(uuid(request.getReservationId(), "reservation_id")),
                request.getPaymentSimulation().isEmpty()
                        ? Optional.empty()
                        : Optional.of(request.getPaymentSimulation()));
        switch (placeOrder.execute(command)) {
            case PlaceOrderResult.Placed placed ->
                complete(
                        response,
                        CreateOrderResponse.newBuilder()
                                .setOrder(toProto(placed.order()))
                                .setIdempotentReplay(placed.replayed())
                                .build());
            case PlaceOrderResult.ReservationNotOrderable notOrderable ->
                throw problem(
                        Status.FAILED_PRECONDITION, GrpcProblems.RESERVATION_NOT_ORDERABLE, notOrderable.reason());
            case PlaceOrderResult.ReservationAlreadyOrdered ordered ->
                throw problem(
                        Status.ALREADY_EXISTS,
                        GrpcProblems.RESERVATION_ALREADY_ORDERED,
                        "Reservation " + ordered.reservationId() + " already has an order");
            case PlaceOrderResult.IdempotencyKeyReused reused ->
                throw problem(
                        Status.INVALID_ARGUMENT,
                        GrpcProblems.IDEMPOTENCY_KEY_REUSED,
                        "This Idempotency-Key was already used for a different request");
        }
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> response) {
        OrderId id = OrderId.of(uuid(request.getOrderId(), "order_id"));
        Optional<UserId> requester = request.getRequesterUserId().isEmpty()
                ? Optional.empty()
                : Optional.of(UserId.of(uuid(request.getRequesterUserId(), "requester_user_id")));
        Order order = queries.find(id, requester)
                .orElseThrow(() ->
                        problem(Status.NOT_FOUND, GrpcProblems.ORDER_NOT_FOUND, "Order " + id + " does not exist"));
        complete(
                response, GetOrderResponse.newBuilder().setOrder(toProto(order)).build());
    }

    @Override
    public void listOrders(ListOrdersRequest request, StreamObserver<ListOrdersResponse> response) {
        int limit = request.getLimit() == 0 ? DEFAULT_PAGE_SIZE : request.getLimit();
        if (limit < 1 || limit > OrderQueries.MAX_PAGE_SIZE) {
            throw problem(
                    Status.INVALID_ARGUMENT,
                    GrpcProblems.VALIDATION_FAILED,
                    "limit must be between 1 and " + OrderQueries.MAX_PAGE_SIZE);
        }
        Optional<PageKey> after =
                request.getCursor().isEmpty() ? Optional.empty() : Optional.of(decodeCursor(request.getCursor()));
        OrderQueries.OrderPage page = queries.list(UserId.of(uuid(request.getUserId(), "user_id")), after, limit);
        ListOrdersResponse.Builder builder = ListOrdersResponse.newBuilder();
        page.orders().forEach(order -> builder.addOrders(toProto(order)));
        page.next().ifPresent(next -> builder.setNextCursor(encodeCursor(next)));
        complete(response, builder.build());
    }

    @Override
    public void replayDeadLetters(
            ReplayDeadLettersRequest request, StreamObserver<ReplayDeadLettersResponse> response) {
        int replayed = replayDeadLetters.execute(
                request.getTopic(), request.getMaxMessages(), required(request.getActorId(), "actor_id"));
        complete(
                response,
                ReplayDeadLettersResponse.newBuilder().setReplayed(replayed).build());
    }

    static com.stockforge.order.v1.Order toProto(Order order) {
        com.stockforge.order.v1.Order.Builder builder = com.stockforge.order.v1.Order.newBuilder()
                .setId(order.id().toString())
                .setUserId(order.userId().toString())
                .setReservationId(order.reservationId().toString())
                .setStatus(order.status().name())
                .setTotalAmount(order.totalAmount().amount().toPlainString())
                .setCurrency(order.totalAmount().currency().getCurrencyCode())
                .setCreatedAt(order.createdAt().toString())
                .setUpdatedAt(order.updatedAt().toString());
        order.items()
                .forEach(item -> builder.addItems(OrderItem.newBuilder()
                        .setProductId(item.productId().toString())
                        .setQuantity(item.quantity())
                        .setUnitPrice(item.unitPrice().amount().toPlainString())
                        .setCurrency(item.unitPrice().currency().getCurrencyCode())
                        .build()));
        order.history()
                .forEach(entry -> builder.addHistory(StatusChange.newBuilder()
                        .setFromStatus(
                                entry.fromStatus() == null
                                        ? ""
                                        : entry.fromStatus().name())
                        .setToStatus(entry.toStatus().name())
                        .setReason(entry.reason())
                        .setOccurredAt(entry.occurredAt().toString())
                        .build()));
        return builder.build();
    }

    private static String encodeCursor(PageKey key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((key.createdAt() + "|" + key.orderId()).getBytes(StandardCharsets.UTF_8));
    }

    private static PageKey decodeCursor(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            return new PageKey(
                    Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException | StringIndexOutOfBoundsException malformed) {
            throw problem(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, "cursor is not valid");
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(required(value, field));
        } catch (IllegalArgumentException malformed) {
            throw problem(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, field + " must be a UUID");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw problem(Status.INVALID_ARGUMENT, GrpcProblems.VALIDATION_FAILED, field + " is required");
        }
        return value;
    }

    private static <T> void complete(StreamObserver<T> observer, T message) {
        observer.onNext(message);
        observer.onCompleted();
    }
}
