package io.gravitee.rest.api.service;

import io.gravitee.rest.api.model.platform.plugin.PluginEntity;

import java.util.Set;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author GraviteeSource Team
 */
public interface PluginService<T extends PluginEntity> {

    Set<T> findAll();

    T findById(String plugin);

    String getSchema(String plugin);

    String getIcon(String plugin);

    String getDocumentation(String plugin);
}
