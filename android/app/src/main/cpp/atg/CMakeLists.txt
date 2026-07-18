
add_library(
             atg
             SHARED
        ATG.c)

target_link_libraries(
             serialport
                       ${log-lib} )
