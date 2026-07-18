
add_library(
             serialport
             SHARED
        SerialPort.c )

target_link_libraries(
             serialport
                       ${log-lib} )
target_link_libraries(serialport log)
