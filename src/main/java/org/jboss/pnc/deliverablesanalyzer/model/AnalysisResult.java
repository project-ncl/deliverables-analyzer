/*
 * Copyright (C) 2019 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.deliverablesanalyzer.model;

import java.util.List;

/**
 * An object container for the results of the analysis
 *
 * Field errorCause is set only in case of a failed analysis.
 *
 * @author Jakub Bartecek &lt;jbartece@redhat.com&gt;
 */
public class AnalysisResult {

    /** Results of the analysis (if analysis was successful) */
    List<FinderResult> results;

    /** Flag indicating if analysis was finished successfully */
    boolean success;

    /** Root cause of the analysis failure (if analysis failer) */
    Throwable errorCause;

    public AnalysisResult(List<FinderResult> results) {
        this.results = results;
        success = true;
    }

    public AnalysisResult(Throwable errorCause) {
        success = false;
        this.errorCause = errorCause;
    }
}
