package com.realtimetradeprocessing.simulator.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.realtimetradeprocessing.simulator.application.ReferenceDataApplicationService;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Reference Data", description = "Account and instrument reference data used by order validation.")
public class ReferenceDataController {

    private final ReferenceDataApplicationService referenceDataApplicationService;

    public ReferenceDataController(ReferenceDataApplicationService referenceDataApplicationService) {
        this.referenceDataApplicationService = referenceDataApplicationService;
    }

    @Operation(summary = "List accounts", description = "Returns account reference data ordered by account ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Accounts returned.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(array = @ArraySchema(schema = @Schema(implementation = AccountResponse.class)))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/accounts")
    List<AccountResponse> getAccounts() {
        return referenceDataApplicationService.getAccounts();
    }

    @Operation(
        summary = "Get account",
        parameters = @Parameter(name = "accountId", in = ParameterIn.PATH, required = true, example = "ACC-001")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account found.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(responseCode = "404", description = "Account not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/accounts/{accountId}")
    AccountResponse getAccount(@PathVariable String accountId) {
        return referenceDataApplicationService.getAccount(accountId);
    }

    @Operation(
        summary = "Create account",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = CreateAccountRequest.class),
                examples = @ExampleObject(name = "Create active account", value = """
                    {
                      "accountId": "ACC-100",
                      "displayName": "Interview Demo Account",
                      "status": "ACTIVE"
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Account created.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or validation failure.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "409", description = "Account already exists.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return referenceDataApplicationService.createAccount(request);
    }

    @Operation(
        summary = "Update account",
        parameters = @Parameter(name = "accountId", in = ParameterIn.PATH, required = true, example = "ACC-100"),
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = UpdateAccountRequest.class),
                examples = @ExampleObject(name = "Suspend account", value = """
                    {
                      "displayName": "Interview Demo Account",
                      "status": "SUSPENDED"
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Account updated.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = AccountResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or validation failure.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "404", description = "Account not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PutMapping("/accounts/{accountId}")
    AccountResponse updateAccount(
        @PathVariable String accountId,
        @Valid @RequestBody UpdateAccountRequest request
    ) {
        return referenceDataApplicationService.updateAccount(accountId, request);
    }

    @Operation(summary = "List instruments", description = "Returns instrument reference data ordered by symbol.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Instruments returned.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(array = @ArraySchema(schema = @Schema(implementation = InstrumentResponse.class)))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/instruments")
    List<InstrumentResponse> getInstruments() {
        return referenceDataApplicationService.getInstruments();
    }

    @Operation(
        summary = "Get instrument",
        parameters = @Parameter(name = "symbol", in = ParameterIn.PATH, required = true, example = "AAPL")
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Instrument found.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = InstrumentResponse.class))),
        @ApiResponse(responseCode = "404", description = "Instrument not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @GetMapping("/instruments/{symbol}")
    InstrumentResponse getInstrument(@PathVariable String symbol) {
        return referenceDataApplicationService.getInstrument(symbol);
    }

    @Operation(
        summary = "Create instrument",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = CreateInstrumentRequest.class),
                examples = @ExampleObject(name = "Create active equity", value = """
                    {
                      "symbol": "IBM",
                      "name": "International Business Machines Corporation",
                      "assetClass": "EQUITY",
                      "status": "ACTIVE",
                      "tickSize": 0.01
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Instrument created.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = InstrumentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or validation failure.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "409", description = "Instrument already exists.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PostMapping("/instruments")
    @ResponseStatus(HttpStatus.CREATED)
    InstrumentResponse createInstrument(@Valid @RequestBody CreateInstrumentRequest request) {
        return referenceDataApplicationService.createInstrument(request);
    }

    @Operation(
        summary = "Update instrument",
        parameters = @Parameter(name = "symbol", in = ParameterIn.PATH, required = true, example = "IBM"),
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                schema = @Schema(implementation = UpdateInstrumentRequest.class),
                examples = @ExampleObject(name = "Halt instrument", value = """
                    {
                      "name": "International Business Machines Corporation",
                      "assetClass": "EQUITY",
                      "status": "HALTED",
                      "tickSize": 0.01
                    }
                    """)
            )
        )
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Instrument updated.", headers = @Header(name = "X-Correlation-Id", ref = "#/components/headers/X-Correlation-Id"), content = @Content(schema = @Schema(implementation = InstrumentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Malformed request or validation failure.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "404", description = "Instrument not found.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse"))),
        @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(ref = "#/components/schemas/ApiErrorResponse")))
    })
    @PutMapping("/instruments/{symbol}")
    InstrumentResponse updateInstrument(
        @PathVariable String symbol,
        @Valid @RequestBody UpdateInstrumentRequest request
    ) {
        return referenceDataApplicationService.updateInstrument(symbol, request);
    }
}
