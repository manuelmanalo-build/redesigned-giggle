package com.realtimetradeprocessing.simulator.fix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.realtimetradeprocessing.simulator.api.SubmitOrderRequest;
import com.realtimetradeprocessing.simulator.domain.OrderSide;
import com.realtimetradeprocessing.simulator.domain.OrderType;

class SimplifiedFixParserTest {

    private final SimplifiedFixParser parser = new SimplifiedFixParser();
    private final NewOrderSingleMapper mapper = new NewOrderSingleMapper();

    @Test
    void parsesPipeDelimitedMessage() {
        SimplifiedFixMessage message = parser.parse(
            "8=FIX.4.4|35=D|49=ACC-001|56=SIM|34=1|52=20260610-14:00:00.000|11=CLIENT-123|55=AAPL|54=1|38=100|40=2|44=185.50|"
        );

        assertThat(message.value("8")).contains("FIX.4.4");
        assertThat(message.requiredValue("35")).isEqualTo("D");
        assertThat(message.requiredValue("11")).isEqualTo("CLIENT-123");
        assertThat(message.requiredValue("44")).isEqualTo("185.50");
    }

    @Test
    void parsesSohDelimitedMessage() {
        SimplifiedFixMessage message = parser.parse(
            "8=FIX.4.4\u000135=D\u000149=ACC-001\u000156=SIM\u000111=CLIENT-123\u000155=MSFT\u000154=2\u000138=25\u000140=1\u0001"
        );

        assertThat(message.requiredValue("35")).isEqualTo("D");
        assertThat(message.requiredValue("55")).isEqualTo("MSFT");
        assertThat(message.requiredValue("54")).isEqualTo("2");
    }

    @Test
    void rejectsMalformedFields() {
        assertThatThrownBy(() -> parser.parse("8=FIX.4.4|35|49=ACC-001"))
            .isInstanceOf(SimplifiedFixParseException.class)
            .hasMessageContaining("Invalid FIX field");
    }

    @Test
    void rejectsDuplicateTags() {
        assertThatThrownBy(() -> parser.parse("35=D|35=F|11=CLIENT-123"))
            .isInstanceOf(SimplifiedFixParseException.class)
            .hasMessageContaining("Duplicate FIX tag");
    }

    @Test
    void rejectsMissingRequiredTagsForOrderMapping() {
        SimplifiedFixMessage message = parser.parse("35=D|49=ACC-001|55=AAPL|54=1|38=100|40=2|44=185.50");

        assertThatThrownBy(() -> mapper.toOrderRequest(message))
            .isInstanceOf(SimplifiedFixMappingException.class)
            .hasMessageContaining("Missing required FIX tag 11");
    }

    @Test
    void rejectsInvalidMessageType() {
        SimplifiedFixMessage message = parser.parse("35=F|49=ACC-001|11=CLIENT-123|55=AAPL|54=1|38=100|40=1");

        assertThatThrownBy(() -> mapper.toOrderRequest(message))
            .isInstanceOf(SimplifiedFixMappingException.class)
            .hasMessageContaining("Unsupported FIX message type");
    }

    @Test
    void mapsLimitNewOrderSingleToOrderRequest() {
        SimplifiedFixMessage message = parser.parse(
            "8=FIX.4.4|35=D|49=ACC-001|56=SIM|34=1|52=20260610-14:00:00.000|11=CLIENT-123|55=AAPL|54=1|38=100|40=2|44=185.50"
        );

        SubmitOrderRequest request = mapper.toOrderRequest(message);

        assertThat(request.clientOrderId()).isEqualTo("CLIENT-123");
        assertThat(request.accountId()).isEqualTo("ACC-001");
        assertThat(request.symbol()).isEqualTo("AAPL");
        assertThat(request.side()).isEqualTo(OrderSide.BUY);
        assertThat(request.type()).isEqualTo(OrderType.LIMIT);
        assertThat(request.quantity()).isEqualTo(100);
        assertThat(request.limitPrice()).isEqualByComparingTo(new BigDecimal("185.50"));
    }

    @Test
    void mapsMarketNewOrderSingleToOrderRequestWithoutPrice() {
        SimplifiedFixMessage message = parser.parse("35=D|49=ACC-002|11=CLIENT-456|55=MSFT|54=2|38=25|40=1|44=300.00");

        SubmitOrderRequest request = mapper.toOrderRequest(message);

        assertThat(request.accountId()).isEqualTo("ACC-002");
        assertThat(request.side()).isEqualTo(OrderSide.SELL);
        assertThat(request.type()).isEqualTo(OrderType.MARKET);
        assertThat(request.quantity()).isEqualTo(25);
        assertThat(request.limitPrice()).isNull();
    }

    @Test
    void rejectsLimitOrderWithoutPrice() {
        SimplifiedFixMessage message = parser.parse("35=D|49=ACC-001|11=CLIENT-123|55=AAPL|54=1|38=100|40=2");

        assertThatThrownBy(() -> mapper.toOrderRequest(message))
            .isInstanceOf(SimplifiedFixMappingException.class)
            .hasMessageContaining("Missing required FIX tag 44");
    }
}
