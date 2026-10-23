package org.rx.crawler.controller;

import org.junit.jupiter.api.Test;
import org.rx.crawler.config.AppConfig;
import org.rx.crawler.config.TaskApiAuthenticationFilter;
import org.rx.crawler.dto.Result;
import org.rx.crawler.task.common.TaskApiException;
import org.rx.crawler.task.order.*;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderControllerTest {

    @Test
    void testUnauthorizedRequest() {
        OrderStore store = mock(OrderStore.class);
        OrderSyncCoordinator coordinator = mock(OrderSyncCoordinator.class);
        AppConfig config = new AppConfig();
        OrderController controller = new OrderController(store, coordinator, config);

        MockHttpServletRequest request = new MockHttpServletRequest();
        // No CALLER_ATTRIBUTE
        TaskApiException ex = assertThrows(TaskApiException.class, () ->
                controller.listOrders(null, null, null, null, null, null, 1, 50, request));
        assertEquals(401, ex.getStatus());
    }

    @Test
    void testListOrdersAuthorized() {
        OrderStore store = mock(OrderStore.class);
        OrderSyncCoordinator coordinator = mock(OrderSyncCoordinator.class);
        AppConfig config = new AppConfig();
        OrderController controller = new OrderController(store, coordinator, config);

        AppConfig.OrderSourceConfig src = new AppConfig.OrderSourceConfig();
        src.setId("jd-main");
        src.setAllowedCallers(List.of("client-app"));
        when(coordinator.resolveSources()).thenReturn(List.of(src));

        OrderPage mockPage = new OrderPage(Collections.emptyList(), 0, 1, 50, true, null, false);
        when(store.queryOrders(any(OrderQueryFilter.class))).thenReturn(mockPage);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TaskApiAuthenticationFilter.CALLER_ATTRIBUTE, "client-app");

        Result<OrderPage> res = controller.listOrders("jd", "jd-main", null, null, null, null, 1, 50, request);
        assertTrue(res.isSuccess());
        assertEquals(0, res.getData().getTotal());
    }

    @Test
    void testListOrdersForbiddenSource() {
        OrderStore store = mock(OrderStore.class);
        OrderSyncCoordinator coordinator = mock(OrderSyncCoordinator.class);
        AppConfig config = new AppConfig();
        OrderController controller = new OrderController(store, coordinator, config);

        AppConfig.OrderSourceConfig src = new AppConfig.OrderSourceConfig();
        src.setId("jd-main");
        src.setAllowedCallers(List.of("other-app")); // Not "client-app"
        when(coordinator.resolveSources()).thenReturn(List.of(src));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TaskApiAuthenticationFilter.CALLER_ATTRIBUTE, "client-app");

        TaskApiException ex = assertThrows(TaskApiException.class, () ->
                controller.listOrders(null, "jd-main", null, null, null, null, 1, 50, request));
        assertEquals(403, ex.getStatus());
    }

    @Test
    void testGetOrderNotFoundAndForbidden() {
        OrderStore store = mock(OrderStore.class);
        OrderSyncCoordinator coordinator = mock(OrderSyncCoordinator.class);
        AppConfig config = new AppConfig();
        OrderController controller = new OrderController(store, coordinator, config);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TaskApiAuthenticationFilter.CALLER_ATTRIBUTE, "client-app");

        when(store.getOrderWithItems(999L)).thenReturn(null);
        TaskApiException exNotFound = assertThrows(TaskApiException.class, () ->
                controller.getOrder(999L, request));
        assertEquals(404, exNotFound.getStatus());

        OrderRecord order = new OrderRecord();
        order.setId(100L);
        order.setSourceId("secret-source");
        when(store.getOrderWithItems(100L)).thenReturn(order);
        when(coordinator.resolveSources()).thenReturn(Collections.emptyList());

        TaskApiException exForbidden = assertThrows(TaskApiException.class, () ->
                controller.getOrder(100L, request));
        assertEquals(403, exForbidden.getStatus());
    }

    @Test
    void testGetSyncStatus() {
        OrderStore store = mock(OrderStore.class);
        OrderSyncCoordinator coordinator = mock(OrderSyncCoordinator.class);
        AppConfig config = new AppConfig();
        OrderController controller = new OrderController(store, coordinator, config);

        AppConfig.OrderSourceConfig src = new AppConfig.OrderSourceConfig();
        src.setId("jd-main");
        src.setAllowedCallers(List.of("client-app"));
        when(coordinator.resolveSources()).thenReturn(List.of(src));

        OrderSyncState state = new OrderSyncState();
        state.setSourceId("jd-main");
        when(store.listSyncStates()).thenReturn(List.of(state));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TaskApiAuthenticationFilter.CALLER_ATTRIBUTE, "client-app");

        Result<List<OrderSyncState>> res = controller.getSyncStatus(null, request);
        assertTrue(res.isSuccess());
        assertEquals(1, res.getData().size());
        assertEquals("jd-main", res.getData().get(0).getSourceId());
    }
}
