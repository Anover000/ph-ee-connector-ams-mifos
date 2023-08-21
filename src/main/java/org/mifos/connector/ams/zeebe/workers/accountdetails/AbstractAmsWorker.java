package org.mifos.connector.ams.zeebe.workers.accountdetails;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.mifos.connector.ams.zeebe.workers.utils.AuthTokenHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
public abstract class AbstractAmsWorker {

	@Value("${fineract.api-url}")
	protected String fineractApiUrl;
	
	@Value("${fineract.datatable-query-api}")
	private String datatableQueryApi;

	@Value("${fineract.column-filter}")
	private String columnFilter;

	@Value("${fineract.result-columns}")
	private String resultColumns;
	
	@Value("${fineract.flags-query-api}")
	private String flagsQueryApi;
	
	@Value("${fineract.flags-column-filter}")
	private String flagsColumnFilter;
	
	@Value("${fineract.flags-result-columns}")
	private String flagsResultColumns;
	
	@Autowired
	private RestTemplate restTemplate;
	
	@Autowired
	private AuthTokenHelper authTokenHelper;
	
	public AbstractAmsWorker() {
	}

	public AbstractAmsWorker(RestTemplate restTemplate) {
		this.restTemplate = restTemplate;
	}
	
	protected AmsDataTableQueryResponse[] lookupAccount(String iban, String tenantId) {
		return exchange(UriComponentsBuilder
				.fromHttpUrl(fineractApiUrl)
				.path(datatableQueryApi)
				.queryParam("columnFilter", columnFilter)
				.queryParam("valueFilter", iban)
				.queryParam("resultColumns", resultColumns)
				.encode().toUriString(),
				AmsDataTableQueryResponse[].class,
				tenantId);
	}
	
	@SuppressWarnings("unchecked")
	protected List<Object> lookupFlags(Long accountId, String tenantId) {
		List<LinkedHashMap<String, Object>> flags = exchange(UriComponentsBuilder
				.fromHttpUrl(fineractApiUrl)
				.path(flagsQueryApi)
				.queryParam("columnFilter", flagsColumnFilter)
				.queryParam("valueFilter", accountId)
				.queryParam("resultColumns", flagsResultColumns)
				.encode().toUriString(),
				List.class,
				tenantId);
		return flags.stream().map(flagResult -> flagResult.get(flagsResultColumns)).collect(Collectors.toList());
	}

	protected <T> T exchange(String urlTemplate, Class<T> responseType, String tenantId) {
		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		httpHeaders.set("Authorization", authTokenHelper.generateAuthToken());
		httpHeaders.set("Fineract-Platform-TenantId", tenantId);
		log.trace("HttpHeaders: {}", httpHeaders);
		return restTemplate.exchange(
				urlTemplate, 
				HttpMethod.GET, 
				new HttpEntity<>(httpHeaders), 
				responseType)
			.getBody();
	}
}