# Apache HTTP Components Metadata Filter

This is a plugin to the Apache HTTP Client (4.x) that blocks access to
internal metadata APIs for most popular cloud environments. This is
meant to help prevent against a class of server-side request forgery
(SSRF) attacks similar to the one used in the [2019 Capital One
breach](https://blog.appsecco.com/an-ssrf-privileged-aws-keys-and-the-capital-one-breach-4c3c2cded3af).

This plugin works by blocking access to all link local addresses as well
as specific well-known metadata host names. It is assumed that clients
with this plugin installed would only need to access user-provided URLs
and would never need to access link local addresses or cloud metadata
APIs.

The implementation code is minimal. Because use cases may vary,
integrators should consider implementing the logic directly if this
plugin does not meet their needs exactly.

## License

   Copyright 2019 Carlos Macasaet

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       https://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and 
   limitations under the License.
