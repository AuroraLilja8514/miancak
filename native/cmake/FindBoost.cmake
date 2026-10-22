# All dependencies are CMake targets from the locked source tree.
set(Boost_FOUND TRUE)
list(TRANSFORM BOOST_INCLUDE_LIBRARIES PREPEND Boost:: OUTPUT_VARIABLE Boost_LIBRARIES)
