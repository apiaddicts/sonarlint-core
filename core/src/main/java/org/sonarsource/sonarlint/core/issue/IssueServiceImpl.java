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
package org.sonarsource.sonarlint.core.issue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonarsource.sonarlint.core.ServerApiProvider;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.AddIssueCommentParams;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.ChangeIssueStatusParams;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.CheckAnticipatedStatusChangeSupportedParams;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.CheckAnticipatedStatusChangeSupportedResponse;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.CheckStatusChangePermittedParams;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.CheckStatusChangePermittedResponse;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.IssueService;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.ReopenAllIssuesForFileParams;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.ReopenIssueParams;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.ReopenIssueResponse;
import org.sonarsource.sonarlint.core.clientapi.backend.issue.ResolutionStatus;
import org.sonarsource.sonarlint.core.commons.LocalOnlyIssue;
import org.sonarsource.sonarlint.core.commons.Transition;
import org.sonarsource.sonarlint.core.commons.Version;
import org.sonarsource.sonarlint.core.local.only.LocalOnlyIssueStorageService;
import org.sonarsource.sonarlint.core.local.only.XodusLocalOnlyIssueStore;
import org.sonarsource.sonarlint.core.repository.config.ConfigurationRepository;
import org.sonarsource.sonarlint.core.serverapi.ServerApi;
import org.sonarsource.sonarlint.core.serverapi.proto.sonarqube.ws.Issues;
import org.sonarsource.sonarlint.core.serverconnection.ServerInfoSynchronizer;
import org.sonarsource.sonarlint.core.serverconnection.storage.ProjectServerIssueStore;
import org.sonarsource.sonarlint.core.storage.StorageService;
import org.sonarsource.sonarlint.core.telemetry.TelemetryServiceImpl;
import org.sonarsource.sonarlint.core.tracking.LocalOnlyIssueRepository;

@Named
@Singleton
public class IssueServiceImpl implements IssueService {

  private static final String STATUS_CHANGE_PERMISSION_MISSING_REASON = "Marking an issue as resolved requires the 'Administer Issues' permission";
  private static final String UNSUPPORTED_SQ_VERSION_REASON = "Marking a local-only issue as resolved requires SonarQube 10.2+";
  private static final Version SQ_ANTICIPATED_TRANSITIONS_MIN_VERSION = Version.create("10.2");

  /** With SQ 10.4 the transitions changed from "Won't fix" to "Accept" */
  private static final Version SQ_ACCEPTED_TRANSITION_MIN_VERSION = Version.create("10.4");
  private static final List<ResolutionStatus> NEW_RESOLUTION_STATUSES = List.of(ResolutionStatus.ACCEPT, ResolutionStatus.FALSE_POSITIVE);
  private static final List<ResolutionStatus> OLD_RESOLUTION_STATUSES = List.of(ResolutionStatus.WONT_FIX, ResolutionStatus.FALSE_POSITIVE);
  private static final Map<ResolutionStatus, Transition> transitionByResolutionStatus = Map.of(
    ResolutionStatus.ACCEPT, Transition.ACCEPT,
    ResolutionStatus.WONT_FIX, Transition.WONT_FIX,
    ResolutionStatus.FALSE_POSITIVE, Transition.FALSE_POSITIVE
  );

  private final ConfigurationRepository configurationRepository;
  private final ServerApiProvider serverApiProvider;
  private final StorageService storageService;
  private final LocalOnlyIssueStorageService localOnlyIssueStorageService;
  private final LocalOnlyIssueRepository localOnlyIssueRepository;
  private final TelemetryServiceImpl telemetryService;

  public IssueServiceImpl(ConfigurationRepository configurationRepository, ServerApiProvider serverApiProvider,
    StorageService storageService, LocalOnlyIssueStorageService localOnlyIssueStorageService,
    TelemetryServiceImpl telemetryService, LocalOnlyIssueRepository localOnlyIssueRepository) {
    this.configurationRepository = configurationRepository;
    this.serverApiProvider = serverApiProvider;
    this.storageService = storageService;
    this.localOnlyIssueStorageService = localOnlyIssueStorageService;
    this.localOnlyIssueRepository = localOnlyIssueRepository;
    this.telemetryService = telemetryService;
  }

  @Override
  public CompletableFuture<Void> changeStatus(ChangeIssueStatusParams params) {
    var configurationScopeId = params.getConfigurationScopeId();
    var optionalBinding = configurationRepository.getEffectiveBinding(configurationScopeId);
    return optionalBinding
      .flatMap(effectiveBinding -> serverApiProvider.getServerApi(effectiveBinding.getConnectionId()))
      .map(connection -> {
        var reviewStatus = transitionByResolutionStatus.get(params.getNewStatus());
        var binding = optionalBinding.get();
        var projectServerIssueStore = storageService.binding(binding).findings();
        var issueKey = params.getIssueKey();
        boolean isServerIssue = projectServerIssueStore.containsIssue(issueKey, params.isTaintIssue());
        if (isServerIssue) {
          return connection.issue().changeStatusAsync(issueKey, reviewStatus)
            .thenAccept(nothing -> projectServerIssueStore.updateIssueResolutionStatus(issueKey, params.isTaintIssue(), true)
              .ifPresent(issue -> telemetryService.issueStatusChanged(issue.getRuleKey())))
            .exceptionally(throwable -> {
              throw new IssueStatusChangeException(throwable);
            });
        }
        return asUUID(issueKey)
          .flatMap(localOnlyIssueRepository::findByKey)
          .map(issue -> {
            var coreStatus = org.sonarsource.sonarlint.core.commons.IssueStatus.valueOf(params.getNewStatus().name());
            issue.resolve(coreStatus);
            var localOnlyIssueStore = localOnlyIssueStorageService.get();
            return connection.issue()
              .anticipatedTransitions(binding.getSonarProjectKey(), concat(localOnlyIssueStore.loadAll(configurationScopeId), issue))
              .thenAccept(nothing -> {
                localOnlyIssueStore.storeLocalOnlyIssue(params.getConfigurationScopeId(), issue);
                telemetryService.issueStatusChanged(issue.getRuleKey());
              });
          }).orElseThrow(() -> new IssueStatusChangeException("Issue key " + issueKey + " was not found"));
      })
      .orElseGet(() -> CompletableFuture.completedFuture(null));
  }

  private static List<LocalOnlyIssue> concat(List<LocalOnlyIssue> issues, LocalOnlyIssue issue) {
    return Stream.concat(issues.stream(), Stream.of(issue)).collect(Collectors.toList());
  }

  private static List<LocalOnlyIssue> subtract(List<LocalOnlyIssue> allIssues, List<LocalOnlyIssue> issueToSubtract) {
    return allIssues.stream()
      .filter(it -> issueToSubtract.stream().noneMatch(issue -> issue.getId().equals(it.getId())))
      .collect(Collectors.toList());
  }

  /**
   *  Check if the anticipated transitions are supported on the server side (requires SonarQube 10.2+)
   *
   *  @param api used for checking if server is a SonarQube instance
   *  @param connectionId required to get the version information from the server
   *  @return whether server is SonarQube instance and matches version requirement
   */
  private boolean checkAnticipatedStatusChangeSupported(ServerApi api, String connectionId) {
    return !api.isSonarCloud() && storageService.connection(connectionId).serverInfo().read()
            .map(version -> version.getVersion().satisfiesMinRequirement(SQ_ANTICIPATED_TRANSITIONS_MIN_VERSION))
            .orElse(false);
  }

  @Override
  public CompletableFuture<CheckAnticipatedStatusChangeSupportedResponse> checkAnticipatedStatusChangeSupported(CheckAnticipatedStatusChangeSupportedParams params) {
    var configScopeId = params.getConfigScopeId();
    var bindingOpt = configurationRepository.getEffectiveBinding(configScopeId);
    if (bindingOpt.isEmpty()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("Binding for configuration scope ID '" + configScopeId + "' does not exist"));
    }
    var binding = bindingOpt.get();
    var connectionId = binding.getConnectionId();
    var serverApiOpt = serverApiProvider.getServerApi(connectionId);
    if (serverApiOpt.isEmpty()) {
      // This (not very testable) corner case can only happen on one occasion:
      //   When the binding was removed between `ConfigurationRepository.getEffectiveBinding(configScopeId)` and now!
      return CompletableFuture.failedFuture(new IllegalArgumentException("Connection with ID '" + connectionId + "' does not exist"));
    }
    var serverApi = serverApiOpt.get();
    return CompletableFuture.completedFuture(new CheckAnticipatedStatusChangeSupportedResponse(checkAnticipatedStatusChangeSupported(serverApi, connectionId)));
  }

  @Override
  public CompletableFuture<CheckStatusChangePermittedResponse> checkStatusChangePermitted(CheckStatusChangePermittedParams params) {
    var connectionId = params.getConnectionId();
    var serverApiOpt = serverApiProvider.getServerApi(connectionId);
    if (serverApiOpt.isEmpty()) {
      return CompletableFuture.failedFuture(new IllegalArgumentException("Connection with ID '" + connectionId + "' does not exist"));
    }
    var issueKey = params.getIssueKey();
    var serverApi = serverApiOpt.get();

    return asUUID(issueKey)
      .flatMap(localOnlyIssueRepository::findByKey)
      .map(r -> {
        // For anticipated issues we currently don't get the information from SonarQube (as there is no web API
        // endpoint) regarding the available transitions. SonarCloud doesn't provide it currently anyway. That's why we
        // have to rely on the version check for SonarQube (>= 10.2 / >=10.4)
        List<ResolutionStatus> statuses = List.of();
        if (checkAnticipatedStatusChangeSupported(serverApi, connectionId)) {
          var is104orNewer = !serverApi.isSonarCloud() && is104orNewer(connectionId, serverApi);
          statuses = is104orNewer ? NEW_RESOLUTION_STATUSES : OLD_RESOLUTION_STATUSES;
        }

        return CompletableFuture.completedFuture(toResponse(statuses, UNSUPPORTED_SQ_VERSION_REASON));
      })
      .orElseGet(() -> serverApi.issue().searchByKey(params.getIssueKey())
        .thenApply(issue -> toResponse(getAdministerIssueTransitions(issue), STATUS_CHANGE_PERMISSION_MISSING_REASON)));
  }

  /** For checking whether SonarQube is already on 10.4 or not. NEVER apply to SonarCloud as their version differs! */
  private boolean is104orNewer(String connectionId, ServerApi serverApi) {
    var serverVersionSynchronizer = new ServerInfoSynchronizer(storageService.connection(connectionId));
    var serverVersion = serverVersionSynchronizer.readOrSynchronizeServerInfo(serverApi);
    return serverVersion.getVersion().compareToIgnoreQualifier(SQ_ACCEPTED_TRANSITION_MIN_VERSION) >= 0;
  }

  private static CheckStatusChangePermittedResponse toResponse(List<ResolutionStatus> statuses, String reason) {
    var permitted = !statuses.isEmpty();

    // No status available means it is not permitted or not supported (e.g. SonarCloud for anticipated issues)
    return new CheckStatusChangePermittedResponse(permitted, permitted ? null : reason, statuses);
  }

  private static List<ResolutionStatus> getAdministerIssueTransitions(Issues.Issue issue) {
    // the 2 required transitions are not available when the 'Administer Issues' permission is missing
    // normally the 'Browse' permission is also required, but we assume it's present as the client knows the issue key
    var possibleTransitions = new HashSet<>(issue.getTransitions().getTransitionsList());

    if (possibleTransitions.containsAll(toTransitionStatus(NEW_RESOLUTION_STATUSES))) {
      return NEW_RESOLUTION_STATUSES;
    }

    // No transitions meaning you're not allowed. That's it.
    return possibleTransitions.containsAll(toTransitionStatus(OLD_RESOLUTION_STATUSES))
      ? OLD_RESOLUTION_STATUSES
      : List.of();
  }

  private static Set<String> toTransitionStatus(List<ResolutionStatus> resolutions) {
    return resolutions.stream()
      .map(resolution -> transitionByResolutionStatus.get(resolution).getStatus())
      .collect(Collectors.toSet());
  }

  @Override
  public CompletableFuture<Void> addComment(AddIssueCommentParams params) {
    var configurationScopeId = params.getConfigurationScopeId();
    var issueKey = params.getIssueKey();
    return asUUID(issueKey)
      .flatMap(issueId -> setCommentOnLocalOnlyIssue(configurationScopeId, issueId, params.getText()))
      .orElseGet(() -> addCommentOnServerIssue(configurationScopeId, issueKey, params.getText()));
  }

  @Override
  public CompletableFuture<ReopenIssueResponse> reopenIssue(ReopenIssueParams params) {
    var configurationScopeId = params.getConfigurationScopeId();
    var optionalBinding = configurationRepository.getEffectiveBinding(configurationScopeId);
    return optionalBinding
      .flatMap(effectiveBinding -> serverApiProvider.getServerApi(effectiveBinding.getConnectionId()))
      .map(connection -> {
        var binding = optionalBinding.get();
        var projectServerIssueStore = storageService.binding(binding).findings();
        var issueId = params.getIssueId();
        boolean isServerIssue = projectServerIssueStore.containsIssue(issueId, params.isTaintIssue());
        if (isServerIssue) {
          return reopenServerIssue(connection, issueId, projectServerIssueStore, params.isTaintIssue());
        } else {
          return reopenLocalIssue(issueId, configurationScopeId);
        }
      })
      .orElseGet(() -> CompletableFuture.completedFuture(new ReopenIssueResponse(false)));
  }

  @Override
  public CompletableFuture<ReopenIssueResponse> reopenAllIssuesForFile(ReopenAllIssuesForFileParams params) {
    var configurationScopeId = params.getConfigurationScopeId();
    var filePath = params.getRelativePath();
    var localOnlyIssueStore = localOnlyIssueStorageService.get();
    return removeAllIssuesForFile(localOnlyIssueStore, configurationScopeId, filePath)
      .thenApply(v -> localOnlyIssueStorageService.get().removeAllIssuesForFile(configurationScopeId, filePath))
      .thenApply(ReopenIssueResponse::new);
  }

  private CompletableFuture<Void> removeAllIssuesForFile(XodusLocalOnlyIssueStore localOnlyIssueStore,
    String configurationScopeId, String filePath) {
    var allIssues = localOnlyIssueStore.loadAll(configurationScopeId);
    var issuesForFile = localOnlyIssueStore.loadForFile(configurationScopeId, filePath);
    var issuesToSync = subtract(allIssues, issuesForFile);
    var optionalBinding = configurationRepository.getEffectiveBinding(configurationScopeId);
    return optionalBinding
      .flatMap(effectiveBinding -> serverApiProvider.getServerApi(effectiveBinding.getConnectionId()))
      .map(connection -> connection.issue().anticipatedTransitions(optionalBinding.get().getSonarProjectKey(), issuesToSync))
      .orElseGet(() -> CompletableFuture.completedFuture(null));
  }

  private CompletableFuture<Void> removeIssueOnServer(XodusLocalOnlyIssueStore localOnlyIssueStore,
    String configurationScopeId, UUID issueId) {
    var allIssues = localOnlyIssueStore.loadAll(configurationScopeId);
    var issuesToSync = allIssues.stream().filter(it -> !it.getId().equals(issueId)).collect(Collectors.toList());
    var optionalBinding = configurationRepository.getEffectiveBinding(configurationScopeId);
    return optionalBinding
      .flatMap(effectiveBinding -> serverApiProvider.getServerApi(effectiveBinding.getConnectionId()))
      .map(connection -> connection.issue().anticipatedTransitions(optionalBinding.get().getSonarProjectKey(), issuesToSync))
      .orElseGet(() -> CompletableFuture.completedFuture(null));
  }

  private Optional<CompletableFuture<Void>> setCommentOnLocalOnlyIssue(String configurationScopeId, UUID issueId, String comment) {
    var localOnlyIssueStore = localOnlyIssueStorageService.get();
    return localOnlyIssueStore.find(issueId)
      .flatMap(commentedIssue -> {
        var resolution = commentedIssue.getResolution();
        if (resolution != null) {
          // should always be true, we store only resolved local-only issues
          resolution.setComment(comment);
          var issuesToSync = localOnlyIssueStore.loadAll(configurationScopeId);
          issuesToSync.replaceAll(issue -> issue.getId().equals(issueId) ? commentedIssue : issue);
          var optionalBinding = configurationRepository.getEffectiveBinding(configurationScopeId);
          return optionalBinding
            .flatMap(effectiveBinding -> serverApiProvider.getServerApi(effectiveBinding.getConnectionId()))
            .map(connection -> connection.issue().anticipatedTransitions(optionalBinding.get().getSonarProjectKey(), issuesToSync))
            .map(future -> future.thenAccept(nothing -> localOnlyIssueStore.storeLocalOnlyIssue(configurationScopeId, commentedIssue)));
        }
        return Optional.empty();
      });
  }

  private CompletableFuture<Void> addCommentOnServerIssue(String configurationScopeId, String issueKey, String comment) {
    var optionalBinding = configurationRepository.getEffectiveBinding(configurationScopeId);
    return optionalBinding
      .flatMap(effectiveBinding -> serverApiProvider.getServerApi(effectiveBinding.getConnectionId()))
      .map(connection -> connection.issue().addComment(issueKey, comment)
        .exceptionally(throwable -> {
          throw new AddIssueCommentException(throwable);
        }))
      .orElseGet(() -> CompletableFuture.completedFuture(null));
  }

  private CompletableFuture<ReopenIssueResponse> reopenServerIssue(ServerApi connection, String issueId, ProjectServerIssueStore projectServerIssueStore, boolean isTaintIssue) {
    return connection.issue().changeStatusAsync(issueId, Transition.REOPEN)
      .thenAccept(nothing -> projectServerIssueStore.updateIssueResolutionStatus(issueId, isTaintIssue, false)
        .ifPresent(issue -> telemetryService.issueStatusChanged(issue.getRuleKey())))
      .thenApply(nothing -> new ReopenIssueResponse(true))
      .exceptionally(throwable -> {
        throw new IssueStatusChangeException(throwable);
      });
  }

  private CompletableFuture<ReopenIssueResponse> reopenLocalIssue(String issueId, String configurationScopeId) {
    var issueUuidOptional = asUUID(issueId);
    if (issueUuidOptional.isEmpty()) {
      return CompletableFuture.completedFuture(new ReopenIssueResponse(false));
    }
    var issueUuid = issueUuidOptional.get();
    var localOnlyIssueStore = localOnlyIssueStorageService.get();
    return removeIssueOnServer(localOnlyIssueStore, configurationScopeId, issueUuid)
      .thenApply(v -> localOnlyIssueStorageService.get().removeIssue(issueUuid))
      .thenApply(ReopenIssueResponse::new);
  }

  private static Optional<UUID> asUUID(String key) {
    try {
      return Optional.of(UUID.fromString(key));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

}
