# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

*** Settings ***
Documentation       Test ozone admin container command
Library             BuiltIn
Resource            ../commonlib.robot
Test Timeout        5 minutes
Suite Setup         Create test data

*** Variables ***
${CONTAINER}
${SCM}       scm

*** Keywords ***
Create test data
    Run Keyword if      '${SECURITY_ENABLED}' == 'true'     Kinit test user     testuser     testuser.keytab
                        Execute          ozone freon ockg -n1 -t1 -p container

Container is closed
    [arguments]     ${container}
    ${output} =         Execute          ozone admin container info "${container}"
                        Should contain   ${output}   CLOSED

*** Test Cases ***
# Basic container operations
Create container
    ${output} =         Execute          ozone admin container create
                        Should contain   ${output}   is created
    ${container} =      Execute          echo "${output}" | grep 'is created' | cut -f2 -d' '
                        Set Suite Variable    ${CONTAINER}    ${container}

Close container
    ${container} =      Execute          ozone admin container list --state OPEN | jq -r '.[] | select(.replicationConfig.replicationFactor == "ONE") | .containerID' | head -1
                        Execute          ozone admin container close "${container}"
    ${output} =         Execute          ozone admin container info "${container}"
                        Should contain   ${output}   CLOS
    Wait until keyword succeeds    1min    10sec    Container is closed    ${container}

# Container info operations
Container info
    ${output} =         Execute          ozone admin container info "${CONTAINER}"
                        Should contain   ${output}   Container id: ${CONTAINER}
                        Should contain   ${output}   Pipeline id
                        Should contain   ${output}   Datanodes

Verbose container info
    ${output} =         Execute          ozone admin --verbose container info "${CONTAINER}"
                        Should contain   ${output}   Pipeline Info

# JSON output validation
Check JSON array structure
    ${output} =         Execute          ozone admin container list
                        Should Start With   ${output}   [
                        Should End With   ${output}   ]
    ${json} =           Execute          echo '${output}' | jq -r '.'
                        Should Contain   ${json}   containerID
                        Should Contain   ${json}   state
                        Should Contain   ${json}   replicationConfig
                        Should Contain   ${json}   pipelineID
                        Should Contain   ${json}   usedBytes
                        Should Contain   ${json}   numberOfKeys
                        Should Contain   ${json}   lastUsed
                        Should Contain   ${json}   owner
                        Should Contain   ${json}   deleteTransactionId

Check JSON array with empty result
    ${output} =         Execute          ozone admin container list --state=INVALID_STATE
                        Should Be Equal   ${output}   [ ]

# Container listing with filters
List containers with state filter
    ${output} =         Execute          ozone admin container list --state=OPEN
                        Should Start With   ${output}   [
                        Should End With   ${output}   ]
    ${states} =         Execute          echo '${output}' | jq -r '.[].state'
                        Should Contain   ${states}   OPEN
                        Should Not Contain   ${states}   CLOSED

List containers with replication config
    ${output} =         Execute          ozone admin container list -t RATIS -r THREE
                        Should Start With   ${output}   [
                        Should End With   ${output}   ]
    ${types} =          Execute          echo '${output}' | jq -r '.[].replicationConfig.type'
                        Should Not Contain   ${types}   EC
    ${factors} =        Execute          echo '${output}' | jq -r '.[].replicationConfig.replicationFactor'
                        Should Not Contain   ${factors}   ONE

# Batch operations
List all containers
    ${output} =         Execute          ozone admin container list --all
                        Should Start With   ${output}   [
                        Should End With   ${output}   ]
    ${count} =          Execute          echo '${output}' | jq -r 'length'
                        Should Be True   ${count} > 0
    ${containerIDs} =   Execute          echo '${output}' | jq -r '.[].containerID'
                        Should Not Be Empty   ${containerIDs}

List containers with batch size
    ${output} =         Execute          ozone admin container list --all --count 10
                        Should Start With   ${output}   [
                        Should End With   ${output}   ]
    ${count} =          Execute          echo '${output}' | jq -r 'length'
                        Should Be True   ${count} <= 10

List containers from start ID
    ${output} =         Execute          ozone admin container list --all --start 2
                        Should Start With   ${output}   [
                        Should End With   ${output}   ]
    ${containerIDs} =   Execute          echo '${output}' | jq -r '.[].containerID'
                        Should Not Be Empty   ${containerIDs}

# Error cases
Incomplete command
    ${output} =         Execute And Ignore Error     ozone admin container
                        Should contain   ${output}   Missing required subcommand
                        Should contain   ${output}   list
                        Should contain   ${output}   info
                        Should contain   ${output}   create
                        Should contain   ${output}   close
                        Should contain   ${output}   report
                        Should contain   ${output}   upgrade

Cannot close container without admin privilege
    Requires admin privilege    ozone admin container close "${CONTAINER}"

Cannot create container without admin privilege
    Requires admin privilege    ozone admin container create

Reset user
    Run Keyword if      '${SECURITY_ENABLED}' == 'true'     Kinit test user     testuser     testuser.keytab

#List containers on unknown host
#    ${output} =         Execute And Ignore Error     ozone admin --verbose container list --scm unknown-host
#                        Should contain   ${output}   Invalid host name
