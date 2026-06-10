package com.realtimetradeprocessing.simulator.fix;

import java.math.BigDecimal;

import com.realtimetradeprocessing.simulator.api.SubmitOrderRequest;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;

public class NewOrderSingleMapper {

    private static final String NEW_ORDER_SINGLE = "D";
    private static final String SIDE_BUY = "1";
    private static final String SIDE_SELL = "2";
    private static final String ORD_TYPE_MARKET = "1";
    private static final String ORD_TYPE_LIMIT = "2";

    public SubmitOrderRequest toOrderRequest(SimplifiedFixMessage message) {
        String messageType = message.requiredValue("35");
        if (!NEW_ORDER_SINGLE.equals(messageType)) {
            throw new SimplifiedFixMappingException("Unsupported FIX message type: " + messageType);
        }

        OrderType orderType = mapOrderType(message.requiredValue("40"));
        BigDecimal limitPrice = orderType == OrderType.LIMIT ? parsePositivePrice(message.requiredValue("44")) : null;

        return new SubmitOrderRequest(
            message.requiredValue("11"),
            message.requiredValue("49"),
            message.requiredValue("55"),
            mapSide(message.requiredValue("54")),
            orderType,
            parsePositiveQuantity(message.requiredValue("38")),
            limitPrice
        );
    }

    private static OrderSide mapSide(String fixSide) {
        return switch (fixSide) {
            case SIDE_BUY -> OrderSide.BUY;
            case SIDE_SELL -> OrderSide.SELL;
            default -> throw new SimplifiedFixMappingException("Unsupported FIX Side value: " + fixSide);
        };
    }

    private static OrderType mapOrderType(String fixOrderType) {
        return switch (fixOrderType) {
            case ORD_TYPE_MARKET -> OrderType.MARKET;
            case ORD_TYPE_LIMIT -> OrderType.LIMIT;
            default -> throw new SimplifiedFixMappingException("Unsupported FIX OrdType value: " + fixOrderType);
        };
    }

    private static long parsePositiveQuantity(String rawQuantity) {
        try {
            long quantity = Long.parseLong(rawQuantity);
            if (quantity <= 0) {
                throw new SimplifiedFixMappingException("FIX OrderQty must be positive");
            }
            return quantity;
        } catch (NumberFormatException exception) {
            throw new SimplifiedFixMappingException("Invalid FIX OrderQty value: " + rawQuantity);
        }
    }

    private static BigDecimal parsePositivePrice(String rawPrice) {
        try {
            BigDecimal price = new BigDecimal(rawPrice);
            if (price.signum() <= 0) {
                throw new SimplifiedFixMappingException("FIX Price must be positive");
            }
            return price;
        } catch (NumberFormatException exception) {
            throw new SimplifiedFixMappingException("Invalid FIX Price value: " + rawPrice);
        }
    }
}
