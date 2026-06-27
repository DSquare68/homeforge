package com.github.dsquare68.homeforge.plugin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PluginMetaService {

	@Autowired
	private PluginMetaClient pluginStoreClient;
	
	public PluginMetaService() {
		
	}
}
