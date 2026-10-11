package com.stockforge.order.domain.port;

import com.stockforge.order.domain.model.OrderId;

public interface IdGenerator {
    OrderId newOrderId();
}
