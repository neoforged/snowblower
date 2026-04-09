/*
 * Copyright (c) NeoForged
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.snowblower.github;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitHubActions {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubActions.class);
    private static boolean githubActions;

    public static void setEnvironment(boolean isGithubActions) {
        githubActions = isGithubActions;
    }

    // https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands#grouping-log-lines
    public static void logStartGroup(Object groupTitle) {
        if (githubActions)
            LOGGER.info("::group::{}", groupTitle);
    }

    public static void logEndGroup() {
        if (githubActions)
            LOGGER.info("::endgroup::");
    }
}
