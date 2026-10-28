package com.heavendoor.ultralab;
interface IUltraDataService {
 String snapshot() = 0;
 void setProjectionActive(boolean active) = 1;
 void restartProjection() = 2;
 void setProjectionMode(int mode) = 3;
 void setPopupTheme(boolean night) = 4;
 void destroy() = 16777114;
}
