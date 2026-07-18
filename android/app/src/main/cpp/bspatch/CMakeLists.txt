
add_library( # Sets the name of the library.
             timBsPatch
             SHARED
        bspatch.c
        bizp/blocksort.c
        bizp/bzip2.c
        bizp/bzip2recover.c
        bizp/bzlib.c
        bizp/compress.c
        bizp/crctable.c
        bizp/decompress.c
        bizp/dlltest.c
        bizp/huffman.c
        bizp/mk251.c
        bizp/randtable.c
        bizp/spewG.c
        bizp/unzcrash.c)


target_link_libraries( # Specifies the target library.
                       timBsPatch
                       ${log-lib} )
