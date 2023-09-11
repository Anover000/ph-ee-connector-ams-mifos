package org.mifos.connector.ams.fineract;

import java.util.Map;

public class ConfigFactory {

	private final Map<String, Map<String, Integer>> paymentTypeIdConfigs;
	
	private final Map<String, Map<String, String>> paymentTypeCodeConfigs;
	
	public ConfigFactory(Map<String, Map<String, Integer>> paymentTypeIdConfigs, Map<String, Map<String, String>> paymentTypeCodeConfigs) {
		this.paymentTypeIdConfigs = paymentTypeIdConfigs;
		this.paymentTypeCodeConfigs = paymentTypeCodeConfigs;
	}
	
	public Config getConfig(String tenant) {
		Map<String, Integer> paymentTypeIdConfig = paymentTypeIdConfigs.getOrDefault(tenant, null);
		if (paymentTypeIdConfig == null) {
			throw new IllegalArgumentException("No tenant payment type Id configuration found for " + tenant);
		}
		
		Map<String, String> paymentTypeCodeConfig = paymentTypeCodeConfigs.getOrDefault(tenant, null);
		
		return new Config(tenant, paymentTypeIdConfig, paymentTypeCodeConfig);
	}
}
