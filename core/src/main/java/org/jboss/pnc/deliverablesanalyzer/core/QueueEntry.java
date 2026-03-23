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
package org.jboss.pnc.deliverablesanalyzer.core;

import org.jboss.pnc.deliverablesanalyzer.model.finder.Checksum;
import org.jboss.pnc.deliverablesanalyzer.model.finder.LicenseInfo;

import java.util.Collections;
import java.util.List;

public record QueueEntry(String sourceUrl,Checksum checksum,List<LicenseInfo>licenses){

// Poison Pill Constant
public static final QueueEntry POISON_PILL=new QueueEntry(null,null,Collections.emptyList());

public QueueEntry{licenses=(licenses!=null)?licenses:Collections.emptyList();}}
