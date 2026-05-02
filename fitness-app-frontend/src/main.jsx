import React from 'react'
import ReactDOM from 'react-dom/client'
import { AuthProvider } from 'react-oauth2-code-pkce'
import { Provider } from 'react-redux'
import { store } from './store/store'
import App from './App'
import { authConfig } from './authConfig'   // assuming you have this

const root = ReactDOM.createRoot(document.getElementById('root'))

root.render(
  <AuthProvider 
    authConfig={authConfig}
    loadingComponent={<div>Loading...</div>}
  >
    <Provider store={store}>
      <App />
    </Provider>
  </AuthProvider>
)