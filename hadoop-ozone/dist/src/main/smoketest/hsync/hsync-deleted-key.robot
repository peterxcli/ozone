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
Documentation       Test HSync behavior with deleted keys
Library             OperatingSystem
Library             String
Library             BuiltIn
Resource            ../commonlib.robot
Resource            ../lib/fs.robot
Resource            ../debug/ozone-debug.robot
Suite Setup         Create volume and bucket

*** Variables ***
${VOLUME}           deleted-hsync-volume
${BUCKET}           deleted-hsync-bucket
${KEY_NAME}         deleted-hsync-key

*** Keywords ***
Create volume and bucket
    Execute             ozone sh volume create /${volume}
    Execute             ozone sh bucket create /${volume}/${bucket}

*** Test Cases ***
Test HSync After Key Deletion
    # Create a key and write some data
    ${path} =     Format FS URL         o3fs     ${VOLUME}    ${BUCKET}
    ${key_path} =     Catenate    SEPARATOR=/    ${path}    ${KEY_NAME}
    
    # Create and write to key
    Execute             ozone sh key put /${volume}/${bucket}/${key_name} /etc/passwd

    # Hsync the key
    Execute             ozone sh key hsync /${volume}/${bucket}/${key_name}
    
    # Delete the key
    Execute             ozone sh key delete /${volume}/${bucket}/${key_name}
    
    # Try to hsync the deleted key - should fail
    ${result} =     Run Keyword And Expect Error    *    Execute    ozone sh key hsync /${volume}/${bucket}/${key_name}
    Should Contain    ${result}    KEY_NOT_FOUND
    
    # Verify key is still deleted
    ${result} =     Run Keyword And Expect Error    *    Execute    ozone sh key info /${volume}/${bucket}/${key_name}
    Should Contain    ${result}    KEY_NOT_FOUND 