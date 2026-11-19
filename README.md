# vpp-dispatch-reliability

[![Tests](https://github.com/Vanneka/vpp-dispatch-reliability/actions/workflows/test.yml/badge.svg)](https://github.com/Vanneka/vpp-dispatch-reliability/actions/workflows/test.yml)

Open-source reference code for keeping a virtual power plant's (VPP's) dispatch correct when the platform's own servers fail: recovering in-progress dispatches, rejecting outdated commands, and confirming device responses.

> **Status:** v0.1.0 released (pre-release); v0.2.0, recovery after a failover, is in progress. Everything runs against a **simulated** fleet. This is a reference to read, run, and test against, not a product, and it makes no claims about real-world performance.

## The problem in 30 seconds

A VPP coordinates thousands of home batteries, but every dispatch decision runs through one software platform, which Idaho National Laboratory calls the "operational 'brain' of a VPP" and "a concentrated digital dependency for grid services" (INL, 2026, §7.2). Devices already have fallbacks when they lose contact (CSIP v2.1, §4.4.2). The platform needs its own: if its server fails partway through a dispatch, the backup must know which devices were already told what, and finish the job without skipping or doubling a command.

Read more: [Failover Isn't Enough: The Half-Finished Dispatch](https://vanneka-okeke.medium.com/failover-isnt-enough-the-half-finished-dispatch-b7815acd1148).

## Quick start

You need Java 21 ([how to install it](docs/getting-started.md)). Gradle downloads itself.

```bash
git clone https://github.com/Vanneka/vpp-dispatch-reliability.git
cd vpp-dispatch-reliability
./gradlew test   # run the tests
./gradlew run    # run one simulated dispatch
```

The demo dispatches 12 kW across four simulated batteries, with one battery's confirmation deliberately lost, and saves every step to `data/demo.db`. Problems or questions: see [Getting started](docs/getting-started.md).

## What's in it

| Release | Adds | Status |
|---|---|---|
| v0.1.0 | Simulated fleet; every step of a dispatch saved to SQLite as it happens | Released |
| v0.2.0 | Commands identified by the utility's event ID; primary and backup with a lease; **recovery after a failover**, so each device ends up with one effective command | In progress |
| v0.3.0 | Rejecting outdated or superseded commands | Planned |
| v0.4.0 | Reliable device responses: retries, each response counted once | Planned |
| v1.0.0 | Fault-injection test harness and a full write-up | Planned |

What changed in each release: [CHANGELOG](CHANGELOG.md).

## Simulation, not a deployment

Two copies of the platform run inside one program, with a lease in SQLite deciding which is in charge. Real platforms get failure detection and leader election from their infrastructure; what that infrastructure doesn't decide is what to do with a dispatch that was half-finished when the old primary stopped. That recovery is what this project shows. Details: [design notes](docs/design.md).

## Learn more

- [Design notes](docs/design.md): decisions, how the code is organized, known limitations
- [Getting started](docs/getting-started.md): installing Java, running the demo, troubleshooting
- Blog series on the software behind VPPs: [post 3](https://vanneka-okeke.medium.com/the-brain-of-a-virtual-power-plant-why-its-reliability-matters-7c3855f928ed), [post 4](https://vanneka-okeke.medium.com/failover-isnt-enough-the-half-finished-dispatch-b7815acd1148)

## Sources

- M. Culler et al., *Virtual Power Plant Architecture and Resilient Design*, INL/RPT-26-89692, Idaho National Laboratory, January 2026. https://inl.gov/content/uploads/2026/01/VPP-Architecture-and-Design-Report__Final.pdf
- Common Smart Inverter Profile Working Group, *Common Smart Inverter Profile: IEEE 2030.5 Implementation Guide for Smart Inverters*, v2.1, March 2018. https://sunspec.org/wp-content/uploads/2025/01/CSIPImplementationGuidev2.103-15-2018.pdf
- G. Zhang, C. Jiang, X. Wang, "Comprehensive review on structure and operation of virtual power plant in electrical system," *IET Generation, Transmission & Distribution* 13(2), 145–156, 2019. https://doi.org/10.1049/iet-gtd.2018.5880

## License

Apache-2.0. See [LICENSE](LICENSE).
