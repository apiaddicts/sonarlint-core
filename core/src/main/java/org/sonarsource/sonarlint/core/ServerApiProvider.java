/*
 * SonarLint Core - Implementation
 * Copyright (C) 2016-2023 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core;

import java.util.Optional;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;
import org.sonarsource.sonarlint.core.commons.log.SonarLintLogger;
import org.sonarsource.sonarlint.core.http.ConnectionAwareHttpClientProvider;
import org.sonarsource.sonarlint.core.http.ConnectionUnawareHttpClientProvider;
import org.sonarsource.sonarlint.core.repository.connection.ConnectionConfigurationRepository;
import org.sonarsource.sonarlint.core.repository.connection.SonarCloudConnectionConfiguration;
import org.sonarsource.sonarlint.core.serverapi.EndpointParams;
import org.sonarsource.sonarlint.core.serverapi.ServerApi;

@Named
@Singleton
public class ServerApiProvider {

  private static final SonarLintLogger LOG = SonarLintLogger.get();
  private final ConnectionConfigurationRepository connectionRepository;
  private final ConnectionAwareHttpClientProvider awareHttpClientProvider;
  private final ConnectionUnawareHttpClientProvider unawareHttpClientProvider;

  public ServerApiProvider(ConnectionConfigurationRepository connectionRepository,
    ConnectionAwareHttpClientProvider awareHttpClientProvider,
    ConnectionUnawareHttpClientProvider unawareHttpClientProvider) {
    this.connectionRepository = connectionRepository;
    this.awareHttpClientProvider = awareHttpClientProvider;
    this.unawareHttpClientProvider = unawareHttpClientProvider;
  }

  public Optional<ServerApi> getServerApi(String connectionId) {
    var params = connectionRepository.getEndpointParams(connectionId);
    if (params.isEmpty()) {
      LOG.debug("Connection '{}' is gone", connectionId);
      return Optional.empty();
    }
    return Optional.of(new ServerApi(params.get(), awareHttpClientProvider.getHttpClient(connectionId)));
  }

  public ServerApi getServerApi(String baseUrl, @Nullable String organization, String token) {
    var params = new EndpointParams(baseUrl, SonarCloudConnectionConfiguration.getSonarCloudUrl().equals(baseUrl), organization);
    return new ServerApi(params, unawareHttpClientProvider.getHttpClient(token));
  }
}
