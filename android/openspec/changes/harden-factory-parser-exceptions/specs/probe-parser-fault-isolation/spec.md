## Purpose

Ensures a malformed or unparseable frame from any of the four vendor probe protocols is contained and logged instead of crashing or silently disabling the thread that processes further readings from that source.

## ADDED Requirements

### Requirement: Serial-port dispatch isolates parser exceptions per probe type
For every probe type dispatched from the serial-port polling loop (`ZhongChengCompany`, `LDCompany`, `LD2Company`), an exception thrown while parsing a received frame SHALL be caught at the dispatch site, logged with the oil-can position and the raw frame, and treated as a failed read for that poll — without terminating the polling loop or propagating past the dispatch site.

#### Scenario: LD2Company receives a malformed frame
- **WHEN** the serial-port polling loop dispatches a received frame to the `LD2Company` parser and parsing throws an exception (e.g. an out-of-bounds frame)
- **THEN** the exception is caught and logged with the oil-can position and raw frame, the read is treated as failed (contributing to the existing error-count/retry handling), and the polling loop continues to the next poll

#### Scenario: LDCompany or ZhongChengCompany receives a malformed frame
- **WHEN** the serial-port polling loop dispatches a received frame to the `LDCompany` or `ZhongChengCompany` parser and parsing throws an exception
- **THEN** the exception is caught and logged with the oil-can position and raw frame, the read is treated as failed, and the polling loop continues to the next poll

### Requirement: PC-protocol handler isolates parser exceptions from its processing thread
The dedicated handler thread that decodes incoming PC-protocol (`VR350Factory`) frames SHALL catch any exception thrown while parsing a received frame, log it with enough context to identify the failure, and continue processing subsequent messages on that thread.

#### Scenario: VR350Factory receives an unparseable PC-protocol frame
- **WHEN** the VR350 handler thread receives a message containing a frame that causes parsing to throw an exception
- **THEN** the exception is caught and logged, the malformed frame is discarded, and the handler thread remains alive and able to process the next message
