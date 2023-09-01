package org.mifos.connector.ams.zeebe.workers.bookamount;

import com.baasflow.commons.events.Event;
import com.baasflow.commons.events.EventService;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.dpc.rt.utils.converter.Camt056ToCamt053Converter;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.spring.client.annotation.JobWorker;
import io.camunda.zeebe.spring.client.annotation.Variable;
import iso.std.iso._20022.tech.json.camt_053_001.BankToCustomerStatementV08;
import iso.std.iso._20022.tech.json.camt_053_001.ReportEntry10;
import iso.std.iso._20022.tech.json.pain_001_001.Pain00100110CustomerCreditTransferInitiationV10MessageSchema;
import iso.std.iso._20022.tech.xsd.camt_056_001.PaymentTransactionInformation31;
import jakarta.xml.bind.JAXBException;
import lombok.extern.slf4j.Slf4j;
import org.mifos.connector.ams.fineract.Config;
import org.mifos.connector.ams.fineract.ConfigFactory;
import org.mifos.connector.ams.log.EventLogUtil;
import org.mifos.connector.ams.log.LogInternalCorrelationId;
import org.mifos.connector.ams.log.TraceZeebeArguments;
import org.mifos.connector.ams.mapstruct.Pain001Camt053Mapper;
import org.mifos.connector.ams.zeebe.workers.utils.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class RevertInAmsWorker extends AbstractMoneyInOutWorker {

    @Autowired
    private Pain001Camt053Mapper camt053Mapper;

    @Value("${fineract.incoming-money-api}")
    protected String incomingMoneyApi;

    @Autowired
    private ConfigFactory paymentTypeConfigFactory;

    @Autowired
    private JAXBUtils jaxbUtils;

    @Autowired
    private BatchItemBuilder batchItemBuilder;

    @Autowired
    private EventService eventService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @JobWorker
    @LogInternalCorrelationId
    @TraceZeebeArguments
    public void revertInAms(JobClient jobClient,
                            ActivatedJob activatedJob,
                            @Variable String internalCorrelationId,
                            @Variable String originalPain001,
                            @Variable Integer conversionAccountAmsId,
                            @Variable Integer disposalAccountAmsId,
                            @Variable String transactionDate,
                            @Variable String paymentScheme,
                            @Variable String transactionGroupId,
                            @Variable String transactionCategoryPurposeCode,
                            @Variable BigDecimal amount,
                            @Variable String transactionFeeCategoryPurposeCode,
                            @Variable BigDecimal transactionFeeAmount,
                            @Variable String tenantIdentifier,
                            @Variable String debtorIban) {
        log.info("revertInAms");
        eventService.auditedEvent(
                eventBuilder -> EventLogUtil.initZeebeJob(activatedJob, "revertInAms", internalCorrelationId, transactionGroupId, eventBuilder),
                eventBuilder -> revertInAms(internalCorrelationId,
                        originalPain001,
                        conversionAccountAmsId,
                        disposalAccountAmsId,
                        transactionDate,
                        paymentScheme,
                        transactionGroupId,
                        transactionCategoryPurposeCode,
                        amount,
                        transactionFeeCategoryPurposeCode,
                        transactionFeeAmount,
                        tenantIdentifier,
                        debtorIban,
                        eventBuilder));
    }

    private Void revertInAms(String internalCorrelationId,
                             String originalPain001,
                             Integer conversionAccountAmsId,
                             Integer disposalAccountAmsId,
                             String transactionDate,
                             String paymentScheme,
                             String transactionGroupId,
                             String transactionCategoryPurposeCode,
                             BigDecimal amount,
                             String transactionFeeCategoryPurposeCode,
                             BigDecimal transactionFeeAmount,
                             String tenantIdentifier,
                             String debtorIban,
                             Event.Builder eventBuilder) {
        try {
            Pain00100110CustomerCreditTransferInitiationV10MessageSchema pain001 = objectMapper.readValue(originalPain001, Pain00100110CustomerCreditTransferInitiationV10MessageSchema.class);

            batchItemBuilder.tenantId(tenantIdentifier);

            String conversionAccountWithdrawRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), conversionAccountAmsId, "withdrawal");

            Config paymentTypeConfig = paymentTypeConfigFactory.getConfig(tenantIdentifier);
            Integer paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionAmount"));

            TransactionBody body = new TransactionBody(
                    transactionDate,
                    amount,
                    paymentTypeId,
                    "",
                    FORMAT,
                    locale);

            objectMapper.setSerializationInclusion(Include.NON_NULL);

            String bodyItem = objectMapper.writeValueAsString(body);

            List<TransactionItem> items = new ArrayList<>();

            batchItemBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);

            ReportEntry10 convertedcamt053Entry = camt053Mapper.toCamt053Entry(pain001.getDocument());
            String camt053Entry = objectMapper.writeValueAsString(convertedcamt053Entry);

            String camt053RelativeUrl = "datatables/transaction_details/$.resourceId";

            TransactionDetails td = new TransactionDetails(
                    internalCorrelationId,
                    camt053Entry,
                    debtorIban,
                    transactionDate,
                    FORMAT,
                    locale,
                    transactionGroupId,
                    transactionCategoryPurposeCode);

            String camt053Body = objectMapper.writeValueAsString(td);

            batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

            if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
                log.debug("Withdrawing fee {} from conversion account {}", transactionFeeAmount, conversionAccountAmsId);

                paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionFee"));

                body = new TransactionBody(
                        transactionDate,
                        transactionFeeAmount,
                        paymentTypeId,
                        "",
                        FORMAT,
                        locale);

                bodyItem = objectMapper.writeValueAsString(body);

                batchItemBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);

                td = new TransactionDetails(
                        internalCorrelationId,
                        camt053Entry,
                        debtorIban,
                        transactionDate,
                        FORMAT,
                        locale,
                        transactionGroupId,
                        transactionFeeCategoryPurposeCode);
                camt053Body = objectMapper.writeValueAsString(td);
                batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
            }

            log.debug("Re-depositing amount {} in disposal account {}", amount, disposalAccountAmsId);

            String disposalAccountDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), disposalAccountAmsId, "deposit");

            paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.DisposalAccount.DepositTransactionAmount"));

            body = new TransactionBody(
                    transactionDate,
                    amount,
                    paymentTypeId,
                    "",
                    FORMAT,
                    locale);

            bodyItem = objectMapper.writeValueAsString(body);

            batchItemBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);

            td = new TransactionDetails(
                    internalCorrelationId,
                    camt053Entry,
                    debtorIban,
                    transactionDate,
                    FORMAT,
                    locale,
                    transactionGroupId,
                    transactionCategoryPurposeCode);

            camt053Body = objectMapper.writeValueAsString(td);

            batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

            if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
                log.debug("Re-depositing fee {} in disposal account {}", transactionFeeAmount, disposalAccountAmsId);

                paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.DisposalAccount.DepositTransactionFee"));

                body = new TransactionBody(
                        transactionDate,
                        transactionFeeAmount,
                        paymentTypeId,
                        "",
                        FORMAT,
                        locale);

                bodyItem = objectMapper.writeValueAsString(body);

                batchItemBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);

                td = new TransactionDetails(
                        internalCorrelationId,
                        camt053Entry,
                        debtorIban,
                        transactionDate,
                        FORMAT,
                        locale,
                        transactionGroupId,
                        transactionFeeCategoryPurposeCode);
                camt053Body = objectMapper.writeValueAsString(td);
                batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
            }

            doBatch(items,
                    tenantIdentifier,
                    disposalAccountAmsId,
                    conversionAccountAmsId,
                    internalCorrelationId,
                    "revertInAms");

        } catch (JsonProcessingException e) {
            // TODO technical error handling
            throw new RuntimeException("failed in revert", e);
        }

        return null;
    }

    @JobWorker
    @LogInternalCorrelationId
    @TraceZeebeArguments
    public void revertWithoutFeeInAms(JobClient jobClient,
                                      ActivatedJob activatedJob,
                                      @Variable String internalCorrelationId,
                                      @Variable String originalPain001,
                                      @Variable Integer conversionAccountAmsId,
                                      @Variable Integer disposalAccountAmsId,
                                      @Variable String paymentScheme,
                                      @Variable String transactionGroupId,
                                      @Variable String transactionCategoryPurposeCode,
                                      @Variable BigDecimal amount,
                                      @Variable String transactionFeeCategoryPurposeCode,
                                      @Variable BigDecimal transactionFeeAmount,
                                      @Variable String tenantIdentifier,
                                      @Variable String debtorIban) {
        log.info("revertWithoutFeeInAms");
        eventService.auditedEvent(
                eventBuilder -> EventLogUtil.initZeebeJob(activatedJob, "revertWithoutFeeInAms", internalCorrelationId, transactionGroupId, eventBuilder),
                eventBuilder -> revertWithoutFeeInAms(internalCorrelationId,
                        originalPain001,
                        conversionAccountAmsId,
                        disposalAccountAmsId,
                        paymentScheme,
                        transactionGroupId,
                        transactionCategoryPurposeCode,
                        amount,
                        transactionFeeCategoryPurposeCode,
                        transactionFeeAmount,
                        tenantIdentifier,
                        debtorIban,
                        eventBuilder));
    }

    private Void revertWithoutFeeInAms(String internalCorrelationId,
                                       String originalPain001,
                                       Integer conversionAccountAmsId,
                                       Integer disposalAccountAmsId,
                                       String paymentScheme,
                                       String transactionGroupId,
                                       String transactionCategoryPurposeCode,
                                       BigDecimal amount,
                                       String transactionFeeCategoryPurposeCode,
                                       BigDecimal transactionFeeAmount,
                                       String tenantIdentifier,
                                       String debtorIban,
                                       Event.Builder eventBuilder) {
        try {
            String transactionDate = LocalDate.now().format(DateTimeFormatter.ofPattern(FORMAT));

            objectMapper.setSerializationInclusion(Include.NON_NULL);

            Pain00100110CustomerCreditTransferInitiationV10MessageSchema pain001 = objectMapper.readValue(originalPain001, Pain00100110CustomerCreditTransferInitiationV10MessageSchema.class);

            batchItemBuilder.tenantId(tenantIdentifier);

            String conversionAccountWithdrawRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), conversionAccountAmsId, "withdrawal");

            Config paymentTypeConfig = paymentTypeConfigFactory.getConfig(tenantIdentifier);
            Integer paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionAmount"));

            TransactionBody body = new TransactionBody(
                    transactionDate,
                    amount,
                    paymentTypeId,
                    "",
                    FORMAT,
                    locale);

            String bodyItem = objectMapper.writeValueAsString(body);

            List<TransactionItem> items = new ArrayList<>();

            batchItemBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);

            ReportEntry10 convertedcamt053Entry = camt053Mapper.toCamt053Entry(pain001.getDocument());
            String camt053Entry = objectMapper.writeValueAsString(convertedcamt053Entry);

            String camt053RelativeUrl = "datatables/transaction_details/$.resourceId";

            TransactionDetails td = new TransactionDetails(
                    internalCorrelationId,
                    camt053Entry,
                    debtorIban,
                    transactionDate,
                    FORMAT,
                    locale,
                    transactionGroupId,
                    transactionCategoryPurposeCode);

            String camt053Body = objectMapper.writeValueAsString(td);

            batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

            if (!BigDecimal.ZERO.equals(transactionFeeAmount)) {
                log.debug("Withdrawing fee {} from conversion account {}", transactionFeeAmount, conversionAccountAmsId);

                paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionFee"));

                body = new TransactionBody(
                        transactionDate,
                        transactionFeeAmount,
                        paymentTypeId,
                        "",
                        FORMAT,
                        locale);

                bodyItem = objectMapper.writeValueAsString(body);

                batchItemBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);

                td = new TransactionDetails(
                        internalCorrelationId,
                        camt053Entry,
                        debtorIban,
                        transactionDate,
                        FORMAT,
                        locale,
                        transactionGroupId,
                        transactionFeeCategoryPurposeCode);
                camt053Body = objectMapper.writeValueAsString(td);
                batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);
            }

            log.debug("Re-depositing amount {} in disposal account {}", amount, disposalAccountAmsId);

            String disposalAccountDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), disposalAccountAmsId, "deposit");

            paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.DisposalAccount.DepositTransactionAmount"));

            body = new TransactionBody(
                    transactionDate,
                    amount,
                    paymentTypeId,
                    "",
                    FORMAT,
                    locale);

            bodyItem = objectMapper.writeValueAsString(body);

            batchItemBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);

            td = new TransactionDetails(
                    internalCorrelationId,
                    camt053Entry,
                    debtorIban,
                    transactionDate,
                    FORMAT,
                    locale,
                    transactionGroupId,
                    transactionCategoryPurposeCode);

            camt053Body = objectMapper.writeValueAsString(td);

            batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

            doBatch(items,
                    tenantIdentifier,
                    disposalAccountAmsId,
                    conversionAccountAmsId,
                    internalCorrelationId,
                    "revertWithoutFeeInAms");

        } catch (JsonProcessingException e) {
            // TODO technical error handling
            throw new RuntimeException("failed in revertWithoutFeeInAms", e);
        }

        return null;
    }

    @JobWorker
    @TraceZeebeArguments
    public void depositTheAmountOnDisposalInAms(JobClient client,
                                                ActivatedJob activatedJob,
                                                @Variable BigDecimal amount,
                                                @Variable Integer conversionAccountAmsId,
                                                @Variable Integer disposalAccountAmsId,
                                                @Variable String tenantIdentifier,
                                                @Variable String paymentScheme,
                                                @Variable String transactionCategoryPurposeCode,
                                                @Variable String camt056,
                                                @Variable String debtorIban) {
        log.info("depositTheAmountOnDisposalInAms");
        eventService.auditedEvent(
                eventBuilder -> EventLogUtil.initZeebeJob(activatedJob, "depositTheAmountOnDisposalInAms",
                        null,
                        null,
                        eventBuilder),
                eventBuilder -> depositTheAmountOnDisposalInAms(amount,
                        conversionAccountAmsId,
                        disposalAccountAmsId,
                        tenantIdentifier,
                        paymentScheme,
                        transactionCategoryPurposeCode,
                        camt056,
                        debtorIban,
                        eventBuilder));
    }

    private Void depositTheAmountOnDisposalInAms(BigDecimal amount,
                                                 Integer conversionAccountAmsId,
                                                 Integer disposalAccountAmsId,
                                                 String tenantIdentifier,
                                                 String paymentScheme,
                                                 String transactionCategoryPurposeCode,
                                                 String camt056,
                                                 String debtorIban,
                                                 Event.Builder eventBuilder) {
        try {
            batchItemBuilder.tenantId(tenantIdentifier);

            String transactionDate = LocalDate.now().format(DateTimeFormatter.ofPattern(FORMAT));

            String conversionAccountWithdrawRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), conversionAccountAmsId, "withdrawal");

            Config paymentTypeConfig = paymentTypeConfigFactory.getConfig(tenantIdentifier);
            Integer paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.ConversionAccount.WithdrawTransactionAmount"));

            TransactionBody body = new TransactionBody(
                    transactionDate,
                    amount,
                    paymentTypeId,
                    "",
                    FORMAT,
                    locale);

            objectMapper.setSerializationInclusion(Include.NON_NULL);

            String bodyItem = objectMapper.writeValueAsString(body);

            List<TransactionItem> items = new ArrayList<>();

            batchItemBuilder.add(items, conversionAccountWithdrawRelativeUrl, bodyItem, false);

            iso.std.iso._20022.tech.xsd.camt_056_001.Document document = jaxbUtils.unmarshalCamt056(camt056);
            Camt056ToCamt053Converter converter = new Camt056ToCamt053Converter();
            BankToCustomerStatementV08 statement = converter.convert(document);

            PaymentTransactionInformation31 paymentTransactionInformation = document
                    .getFIToFIPmtCxlReq()
                    .getUndrlyg().get(0)
                    .getTxInf().get(0);

            String originalDebtorBic = paymentTransactionInformation
                    .getOrgnlTxRef()
                    .getDbtrAgt()
                    .getFinInstnId()
                    .getBIC();
            String originalCreationDate = paymentTransactionInformation
                    .getOrgnlGrpInf()
                    .getOrgnlCreDtTm()
                    .toGregorianCalendar()
                    .toZonedDateTime()
                    .toLocalDate()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String originalEndToEndId = paymentTransactionInformation
                    .getOrgnlEndToEndId();

            String internalCorrelationId = String.format("%s_%s_%s", originalDebtorBic, originalCreationDate, originalEndToEndId);
            String camt053 = objectMapper.writeValueAsString(statement);

            String camt053RelativeUrl = "datatables/transaction_details/$.resourceId";

            TransactionDetails td = new TransactionDetails(
                    internalCorrelationId,
                    camt053,
                    debtorIban,
                    transactionDate,
                    FORMAT,
                    locale,
                    internalCorrelationId,
                    transactionCategoryPurposeCode);

            String camt053Body = objectMapper.writeValueAsString(td);

            batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

            String disposalAccountDepositRelativeUrl = String.format("%s%d/transactions?command=%s", incomingMoneyApi.substring(1), disposalAccountAmsId, "deposit");

            paymentTypeId = paymentTypeConfig.findByOperation(String.format("%s.%s", paymentScheme, "revertInAms.DisposalAccount.DepositTransactionAmount"));

            body = new TransactionBody(
                    transactionDate,
                    amount,
                    paymentTypeId,
                    "",
                    FORMAT,
                    locale);

            bodyItem = objectMapper.writeValueAsString(body);

            batchItemBuilder.add(items, disposalAccountDepositRelativeUrl, bodyItem, false);

            td = new TransactionDetails(
                    internalCorrelationId,
                    camt053,
                    debtorIban,
                    transactionDate,
                    FORMAT,
                    locale,
                    internalCorrelationId,
                    transactionCategoryPurposeCode);

            camt053Body = objectMapper.writeValueAsString(td);

            batchItemBuilder.add(items, camt053RelativeUrl, camt053Body, true);

            doBatch(items,
                    tenantIdentifier,
                    disposalAccountAmsId,
                    conversionAccountAmsId,
                    internalCorrelationId,
                    "depositTheAmountOnDisposalInAms");

        } catch (JAXBException | JsonProcessingException e) {
            // TODO technical error handling
            throw new RuntimeException("depositTheAmountOnDisposalInAms failed", e);
        }
        return null;
    }
}