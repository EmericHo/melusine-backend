package fr.polytech.melusine.controllers;

import fr.polytech.melusine.models.dtos.requests.OrderItemRequest;
import fr.polytech.melusine.models.dtos.requests.OrderRequest;
import fr.polytech.melusine.models.dtos.responses.OrderItemResponse;
import fr.polytech.melusine.models.dtos.responses.OrderResponse;
import fr.polytech.melusine.services.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping(path = "/orders", produces = "application/json; charset=UTF-8")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@RequestBody @Valid OrderRequest orderRequest) {
        return orderService.createOrder(orderRequest);
    }

    @PostMapping(path = "/items/{itemId}")
    @ResponseStatus(HttpStatus.OK)
    public void updateOrderStatus(@PathVariable String itemId, @RequestBody OrderItemRequest request) {
        orderService.updateOrderStatus(itemId, request);
    }

    @GetMapping(path = "/items")
    @ResponseStatus(HttpStatus.OK)
    public Page<OrderItemResponse> getOrderItems(
            @PageableDefault(size = 20, page = 0, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable
    ) {
        return orderService.getOrderItems(pageable);
    }

    @GetMapping(path = "/items/{userId}")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderItemResponse> getLastOrderItemsByUser(
            @PathVariable String userId
    ) {
        return orderService.getLastOrderItemsByUserId(userId);
    }

    @GetMapping(path = "/items/last")
    @ResponseStatus(HttpStatus.OK)
    public Page<OrderItemResponse> getLastOrderItems(
            @PageableDefault(size = 20, page = 0, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return orderService.getLastOrderItems(pageable);
    }

}
