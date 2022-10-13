package org.mifos.connector.ams.zeebe.workers;

import java.util.Map;
import java.util.Optional;

import org.apache.commons.validator.routines.IBANValidator;
import org.apache.fineract.client.models.GetSavingsAccountsAccountIdResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;

@Component
public class AmsWorker implements JobHandler {
	
	@Autowired
	private RestTemplate restTemplate;
	
	@Value("${fineract.api-url}")
	private String fineractApiUrl;
	
	@Value("${fineract.datatable-query-api}")
	private String datatableQueryApi;
	
	@Value("${fineract.column-filter}")
	private String columnFilter;
	
	@Value("${fineract.result-columns}")
	private String resultColumns;
	
	Logger logger = LoggerFactory.getLogger(AmsWorker.class);

    private IBANValidator ibanValidator = IBANValidator.DEFAULT_IBAN_VALIDATOR;

    private static final String[] ACCEPTED_CURRENCIES = new String[] { "HUF" };

    public AmsWorker() {
    }
    
    public AmsWorker(RestTemplate restTemplate) {
    	this.restTemplate = restTemplate;
    }

	@Override
	@SuppressWarnings("unchecked")
    public void handle(JobClient jobClient, ActivatedJob activatedJob) {
		logger.error("AmsWorker has started");
        Map<String, Object> variables = activatedJob.getVariablesAsMap();
        AccountAmsStatus status = AccountAmsStatus.READY_TO_RECEIVE_MONEY;
        GetSavingsAccountsAccountIdResponse fiatCurrency = null;
        GetSavingsAccountsAccountIdResponse eCurrency = null;

        String iban = (String) variables.get("valueFilter");
        
        logger.error("Got IBAN " + iban);

        if (ibanValidator.isValid(iban)) {
        	
			/*
			 * HttpHeaders headers = new HttpHeaders(); headers.set(HttpHeaders.ACCEPT,
			 * MediaType.APPLICATION_JSON_VALUE); HttpEntity<?> entity = new
			 * HttpEntity<>(headers);
			 * 
			 * String urlTemplate = UriComponentsBuilder.fromHttpUrl(fineractApiUrl + "/" +
			 * datatableQueryApi) // .queryParam("columnFilter", columnFilter) //
			 * .queryParam("valueFilter", iban) // .queryParam("resultColumns",
			 * resultColumns) .encode() .toUriString();
			 * 
			 * Map<String, Object> params = new HashMap<>(); params.put("columnFilter",
			 * columnFilter); params.put("valueFilter", iban); params.put("resultColumns",
			 * resultColumns);
			 * 
			 * HttpEntity<Object> responseObject = restTemplate.exchange( urlTemplate,
			 * HttpMethod.GET, entity, Object.class, params );
			 * 
			 * List<AmsDataTableQueryResponse> response = (List<AmsDataTableQueryResponse>)
			 * responseObject.getBody();
			 * 
			 * AmsDataTableQueryResponse responseItem = response.get(0);
			 * 
			 * Long accountFiatCurrencyId = responseItem.fiat_account_id(); Long
			 * accountECurrencyId = responseItem.ecurrency_account_id();
			 * 
			 * FineractClient fineractClient = FineractClient.builder().build();
			 * 
			 * SavingsAccountApi savingsAccounts = fineractClient.savingsAccounts; try {
			 * fiatCurrency = savingsAccounts.retrieveOne24(accountFiatCurrencyId, false,
			 * null).execute().body(); eCurrency =
			 * savingsAccounts.retrieveOne24(accountECurrencyId, false,
			 * null).execute().body();
			 * 
			 * if (Arrays.stream(ACCEPTED_CURRENCIES).anyMatch(fiatCurrency.getCurrency().
			 * getCode()::equalsIgnoreCase) && fiatCurrency.getStatus().getId() == 300 &&
			 * eCurrency.getStatus().getId() == 300) { status =
			 * AccountAmsStatus.READY_TO_RECEIVE_MONEY; }
			 * 
			 * } catch (IOException e) { logger.error(e.getMessage(), e); }
			 */
        }
        
        variables.put("accountAmsStatus", status.name());
        variables.put("eCurrencyAccountAmsId", Optional.ofNullable(eCurrency).map(GetSavingsAccountsAccountIdResponse::getId).orElse(1));
        variables.put("fiatCurrencyAccountAmsId", Optional.ofNullable(fiatCurrency).map(GetSavingsAccountsAccountIdResponse::getId).orElse(2));

        jobClient.newCompleteCommand(activatedJob.getKey())
                .variables(variables)
                .send();
    }
}
